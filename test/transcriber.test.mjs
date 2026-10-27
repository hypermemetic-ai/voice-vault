/**
 * Regression tests for the hardened Whisper backend cascade.
 *
 * These cover the failure modes from .architect/ticket.md: a warm daemon that
 * answers with an error status or `{ ok: false }`, a daemon that never answers,
 * and a GPU that cannot serve the request. In every case the pipeline must fall
 * through to the next backend instead of hanging or returning empty text.
 *
 * Everything here is hermetic: the daemon is a throwaway unix-socket HTTP
 * server and `handy` is a stub script, so no GPU or model is required.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";

// Isolate the SQLite history/profile store before any project module loads it.
const TMP_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), "vv-transcriber-"));
process.env.VOICE_VAULT_DB = path.join(TMP_ROOT, "history.db");
process.env.VOICE_VAULT_STORAGE = path.join(TMP_ROOT, "raw");

// Ambient overrides from the host must not leak into these assertions.
for (const key of [
  "WHISPER_SOCKET",
  "WHISPER_SOCKET_TIMEOUT_MS",
  "HANDY_BIN",
  "HANDY_TIMEOUT_MS",
  "HANDY_DEVICE_INDEX_GPU",
  "HANDY_DEVICE_INDEX_IGPU",
  "HANDY_DEVICE_INDEX_CPU",
  "WHISPER_MODEL",
  "VOICE_VAULT_PIECE_MAX_MS",
  "VOICE_VAULT_PIECE_WINDOW_START_MS",
  "VOICE_VAULT_PIECE_WINDOW_END_MS",
  "VOICE_VAULT_PIECE_OVERLAP_MS",
]) {
  delete process.env[key];
}

const {
  WHISPER_SOCKET_TIMEOUT_MS,
  HANDY_TIMEOUT_MS,
  HANDY_DEVICE_INDEX,
  transcribeTiers,
  queryWhisperSocket,
  queryHandyDirect,
  defaultTranscribe,
  transcribeAudioFile,
} = await import("../src/transcriber.mjs");
const { decodeWav, encodePcm16Wav } = await import("../src/wav.mjs");

const ENV_KEYS = [
  "WHISPER_SOCKET",
  "WHISPER_SOCKET_TIMEOUT_MS",
  "HANDY_BIN",
  "HANDY_TIMEOUT_MS",
  "HANDY_DEVICE_INDEX_GPU",
  "HANDY_DEVICE_INDEX_IGPU",
  "HANDY_DEVICE_INDEX_CPU",
  "WHISPER_MODEL",
  "VOICE_VAULT_PIECE_MAX_MS",
  "VOICE_VAULT_PIECE_WINDOW_START_MS",
  "VOICE_VAULT_PIECE_WINDOW_END_MS",
  "VOICE_VAULT_PIECE_OVERLAP_MS",
];

/** Run `fn` with the given env overrides, restoring the previous values after. */
async function withEnv(values, fn) {
  const saved = new Map(ENV_KEYS.map((key) => [key, process.env[key]]));
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = String(value);
  }
  try {
    return await fn();
  } finally {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

function tempDir(label) {
  return fs.mkdtempSync(path.join(os.tmpdir(), `vv-${label}-`));
}

/** Start an HTTP server on a private unix socket inside `dir`. */
function listenOnSocket(dir, handler) {
  const socketPath = path.join(dir, "whisper.sock");
  return new Promise((resolve) => {
    const server = http.createServer(handler);
    server.listen(socketPath, () => resolve({ server, socketPath }));
  });
}

function closeServer(server) {
  return new Promise((resolve) => {
    server.closeAllConnections?.();
    server.close(() => resolve());
  });
}

/** Write an executable stub `handy` that records the device index it was given. */
function writeStubHandy(file, { marker, successIndex = null }) {
  const script = [
    "#!/usr/bin/env bash",
    'index=""',
    'while [ $# -gt 0 ]; do',
    '  case "$1" in',
    '    --device-index) index="${2:-}"; shift 2 ;;',
    "    *) shift ;;",
    "  esac",
    "done",
    `printf '%s\\n' "$index" >> "${marker}"`,
    successIndex === null
      ? 'echo "stub handy always fails" >&2\nexit 3'
      : `if [ "$index" = "${successIndex}" ]; then\n  printf '%s' '{"text":"cpu transcript","transcribe_ms":[42]}'\n  exit 0\nfi\necho "device $index unavailable" >&2\nexit 3`,
    "",
  ].join("\n");
  fs.writeFileSync(file, script, { mode: 0o755 });
  return file;
}

// ---------------------------------------------------------------------------
// Ticket item 1: bounded socket timeout
// ---------------------------------------------------------------------------

test("whisper socket timeout is capped at 15s", () => {
  assert.equal(WHISPER_SOCKET_TIMEOUT_MS, 15_000);
  assert.ok(WHISPER_SOCKET_TIMEOUT_MS < 60_000, "must be far below the old 10-minute cap");
});

test("cascade has four ordered tiers: daemon, dGPU, iGPU, CPU", () => {
  const tiers = transcribeTiers();
  assert.deepEqual(
    tiers.map((tier) => tier.backend),
    ["daemon_gpu", "handy_direct_gpu", "handy_direct_igpu", "handy_direct_cpu"],
  );
  assert.deepEqual(
    tiers.map((tier) => tier.kind),
    ["socket", "handy", "handy", "handy"],
  );
  assert.deepEqual(
    tiers.slice(1).map((tier) => tier.deviceIndex),
    [HANDY_DEVICE_INDEX.gpu, HANDY_DEVICE_INDEX.igpu, HANDY_DEVICE_INDEX.cpu],
  );
  assert.equal(HANDY_DEVICE_INDEX.gpu, 1, "RTX A2000 is device index 1");
  assert.equal(HANDY_DEVICE_INDEX.igpu, 0, "Radeon 780M is device index 0");
  assert.equal(HANDY_DEVICE_INDEX.cpu, 2, "CPU is device index 2");
  assert.equal(HANDY_TIMEOUT_MS, 600_000);
});

// ---------------------------------------------------------------------------
// Ticket item 2: reject non-200 and { ok: false } instead of returning text
// ---------------------------------------------------------------------------

test("queryWhisperSocket resolves a clean 200 payload", async () => {
  const dir = tempDir("socket-ok");
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, text: "hello from daemon", transcribe_ms: 12 }));
  });
  try {
    const parsed = await queryWhisperSocket("/tmp/audio.wav", "req-ok", { socketPath, timeoutMs: 2_000 });
    assert.equal(parsed.text, "hello from daemon");
    assert.equal(parsed.ok, true);
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryWhisperSocket rejects on a non-200 HTTP status", async () => {
  const dir = tempDir("socket-500");
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: "daemon exploded" }));
  });
  try {
    await assert.rejects(
      queryWhisperSocket("/tmp/audio.wav", "req-500", { socketPath, timeoutMs: 2_000 }),
      /HTTP 500.*daemon exploded/s,
    );
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryWhisperSocket rejects on an { ok: false } payload", async () => {
  const dir = tempDir("socket-notok");
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: "no VRAM left" }));
  });
  try {
    await assert.rejects(
      queryWhisperSocket("/tmp/audio.wav", "req-notok", { socketPath, timeoutMs: 2_000 }),
      /reported failure.*no VRAM left/s,
    );
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryWhisperSocket rejects malformed JSON", async () => {
  const dir = tempDir("socket-badjson");
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("<html>not json</html>");
  });
  try {
    await assert.rejects(
      queryWhisperSocket("/tmp/audio.wav", "req-bad", { socketPath, timeoutMs: 2_000 }),
      /Invalid JSON/,
    );
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryWhisperSocket times out a wedged daemon and rejects", async () => {
  const dir = tempDir("socket-hang");
  // Accept the connection and never answer: exactly the "Processing..." hang.
  const { server, socketPath } = await listenOnSocket(dir, () => {});
  const started = Date.now();
  try {
    await assert.rejects(
      queryWhisperSocket("/tmp/audio.wav", "req-hang", { socketPath, timeoutMs: 150 }),
      /timeout after 150ms/,
    );
    assert.ok(Date.now() - started < 5_000, "timeout must fire promptly");
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// handy direct tiers
// ---------------------------------------------------------------------------

test("queryHandyDirect parses handy JSON output", async () => {
  const dir = tempDir("handy-ok");
  const bin = path.join(dir, "handy");
  fs.writeFileSync(bin, '#!/usr/bin/env bash\nprintf \'%s\' \'{"text":"direct transcript"}\'\n', { mode: 0o755 });
  try {
    const result = await queryHandyDirect("/tmp/audio.wav", { bin, deviceIndex: 1, timeoutMs: 5_000 });
    assert.equal(result.ok, true);
    assert.equal(result.text, "direct transcript");
    assert.ok(Number.isFinite(result.transcribe_ms));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryHandyDirect rejects a non-zero exit with the device index", async () => {
  const dir = tempDir("handy-fail");
  const bin = path.join(dir, "handy");
  fs.writeFileSync(bin, '#!/usr/bin/env bash\necho "vulkan OOM" >&2\nexit 2\n', { mode: 0o755 });
  try {
    await assert.rejects(
      queryHandyDirect("/tmp/audio.wav", { bin, deviceIndex: 0, timeoutMs: 5_000 }),
      /handy process failed \(code 2, device 0\).*vulkan OOM/s,
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("queryHandyDirect kills a hanging process at the timeout", async () => {
  const dir = tempDir("handy-hang");
  const bin = path.join(dir, "handy");
  fs.writeFileSync(bin, '#!/usr/bin/env bash\nsleep 30\n', { mode: 0o755 });
  const started = Date.now();
  try {
    await assert.rejects(
      queryHandyDirect("/tmp/audio.wav", { bin, deviceIndex: 1, timeoutMs: 200 }),
      /handy timed out after 200ms \(device 1\)/,
    );
    assert.ok(Date.now() - started < 5_000, "hung handy must be killed promptly");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// Ticket item 3: the four-tier cascade
// ---------------------------------------------------------------------------

test("defaultTranscribe uses the socket daemon when it is healthy", async () => {
  const dir = tempDir("cascade-socket");
  const marker = path.join(dir, "handy-calls.txt");
  const bin = writeStubHandy(path.join(dir, "handy"), { marker, successIndex: "2" });
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, text: "daemon transcript", transcribe_ms: 7 }));
  });
  try {
    const result = await withEnv({ WHISPER_SOCKET: socketPath, HANDY_BIN: bin }, () =>
      defaultTranscribe("/tmp/audio.wav", "req-daemon"),
    );
    assert.equal(result.backend, "daemon_gpu");
    assert.equal(result.text, "daemon transcript");
    assert.deepEqual(result.attempts, []);
    assert.equal(fs.existsSync(marker), false, "handy must not be spawned when the daemon answered");
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("defaultTranscribe cascades daemon -> dGPU -> iGPU -> CPU", async () => {
  const dir = tempDir("cascade-all");
  const marker = path.join(dir, "handy-calls.txt");
  const bin = writeStubHandy(path.join(dir, "handy"), { marker, successIndex: "2" });
  // Daemon is up but reports an error payload: the old code returned empty text
  // here and left the client stuck on "Processing...".
  const { server, socketPath } = await listenOnSocket(dir, (req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: "model not loaded" }));
  });
  try {
    const result = await withEnv({ WHISPER_SOCKET: socketPath, HANDY_BIN: bin }, () =>
      defaultTranscribe("/tmp/audio.wav", "req-cascade"),
    );
    assert.equal(result.backend, "handy_direct_cpu");
    assert.equal(result.text, "cpu transcript");
    assert.deepEqual(
      fs.readFileSync(marker, "utf8").trim().split("\n"),
      ["1", "0", "2"],
      "handy must be tried on the dGPU, then the iGPU, then the CPU",
    );
    assert.deepEqual(
      result.attempts.map((attempt) => attempt.backend),
      ["daemon_gpu", "handy_direct_gpu", "handy_direct_igpu"],
    );
    assert.match(result.attempts[0].error, /model not loaded/);
    assert.match(result.attempts[1].error, /device 1 unavailable/);
    assert.match(result.attempts[2].error, /device 0 unavailable/);
  } finally {
    await closeServer(server);
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("defaultTranscribe reports every backend when all of them fail", async () => {
  const dir = tempDir("cascade-dead");
  const marker = path.join(dir, "handy-calls.txt");
  const bin = writeStubHandy(path.join(dir, "handy"), { marker });
  try {
    await withEnv(
      {
        WHISPER_SOCKET: path.join(dir, "missing.sock"),
        HANDY_BIN: bin,
        HANDY_DEVICE_INDEX_GPU: 1,
        HANDY_DEVICE_INDEX_IGPU: 0,
        HANDY_DEVICE_INDEX_CPU: 2,
      },
      async () => {
        await assert.rejects(defaultTranscribe("/tmp/audio.wav", "req-dead"), (error) => {
          assert.match(error.message, /All Whisper backends failed/);
          assert.deepEqual(
            error.attempts.map((attempt) => attempt.backend),
            ["daemon_gpu", "handy_direct_gpu", "handy_direct_igpu", "handy_direct_cpu"],
          );
          return true;
        });
      },
    );
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("defaultTranscribe honours per-tier env overrides and injected queries", async () => {
  const calls = [];
  const result = await defaultTranscribe("/tmp/audio.wav", "req-injected", {
    tiers: [
      { backend: "daemon_gpu", kind: "socket" },
      { backend: "handy_direct_cpu", kind: "handy", deviceIndex: 9 },
    ],
    socketQuery: async () => {
      calls.push("socket");
      throw new Error("ECONNREFUSED");
    },
    handyQuery: async (_wav, options) => {
      calls.push(`handy:${options.deviceIndex}`);
      return { text: "injected", transcribe_ms: 1 };
    },
  });
  assert.deepEqual(calls, ["socket", "handy:9"]);
  assert.equal(result.backend, "handy_direct_cpu");
  assert.equal(result.text, "injected");
  assert.equal(result.attempts.length, 1);
});

// ---------------------------------------------------------------------------
// Direct tiers use the configured model (design D8)
// ---------------------------------------------------------------------------

test("queryHandyDirect passes WHISPER_MODEL to handy instead of turbo", async () => {
  const dir = tempDir("handy-model");
  const bin = path.join(dir, "handy");
  const argvFile = path.join(dir, "argv.txt");
  fs.writeFileSync(
    bin,
    [
      "#!/usr/bin/env bash",
      `printf '%s\\n' "$@" > "${argvFile}"`,
      `printf '%s' '{"text":"model stub","transcribe_ms":1}'`,
      "",
    ].join("\n"),
    { mode: 0o755 },
  );
  const modelIn = () => {
    const argv = fs.readFileSync(argvFile, "utf8").trim().split("\n");
    return argv[argv.indexOf("--model") + 1];
  };
  try {
    await withEnv({ WHISPER_MODEL: "bark-3.0-clean-Q8_0" }, async () => {
      const result = await queryHandyDirect("/tmp/audio.wav", { bin, deviceIndex: 1, timeoutMs: 5_000 });
      assert.equal(result.text, "model stub");
      assert.equal(modelIn(), "bark-3.0-clean-Q8_0");
    });

    await withEnv({ WHISPER_MODEL: undefined }, async () => {
      await queryHandyDirect("/tmp/audio.wav", { bin, deviceIndex: 1, timeoutMs: 5_000 });
      assert.equal(modelIn(), "turbo", "hosts without configuration keep the old default");
    });
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

// ---------------------------------------------------------------------------
// Chunked long-recording transcription (design D3-D6)
// ---------------------------------------------------------------------------

const PIECE_SR = 16000;

function seconds(value) {
  return Math.round(value * PIECE_SR);
}

/** Flat continuous tone: no pause, so the cutter must force its cuts. */
function flatTone(durationSeconds, amplitude = 6000, frequency = 180) {
  const samples = new Int16Array(seconds(durationSeconds));
  for (let i = 0; i < samples.length; i += 1) {
    samples[i] = Math.round(Math.sin((2 * Math.PI * frequency * i) / PIECE_SR) * amplitude);
  }
  return samples;
}

/** Speech-ish amplitude-modulated burst (same shape as the vad tests). */
function speechBurst(durationSeconds, amplitude = 6000, frequency = 180) {
  const samples = new Int16Array(seconds(durationSeconds));
  for (let i = 0; i < samples.length; i += 1) {
    const t = i / PIECE_SR;
    const envelope = 0.55 + 0.45 * Math.sin(2 * Math.PI * 3.1 * t);
    const value =
      Math.sin(2 * Math.PI * frequency * t) * 0.7 +
      Math.sin(2 * Math.PI * 2 * frequency * t) * 0.3;
    samples[i] = Math.round(value * envelope * amplitude);
  }
  return samples;
}

function silencePcm(durationSeconds) {
  return new Int16Array(seconds(durationSeconds));
}

function concatPcm(chunks) {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Int16Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

function writeSyntheticWav(dir, name, pcm) {
  const file = path.join(dir, name);
  fs.writeFileSync(file, encodePcm16Wav(pcm, PIECE_SR));
  return file;
}

function wavDurationMs(wavPath) {
  const decoded = decodeWav(fs.readFileSync(wavPath));
  return decoded ? (decoded.samples.length / decoded.sampleRate) * 1000 : 0;
}

test("a short recording is one request and reports one piece", async () => {
  const dir = tempDir("piece-short");
  const file = writeSyntheticWav(dir, "short.wav", speechBurst(20));
  const calls = [];
  try {
    const result = await transcribeAudioFile(file, "short-piece", {
      speakerGate: false,
      transcribe: async (wavPath, reqId) => {
        calls.push({ reqId, durationMs: wavDurationMs(wavPath) });
        return { text: "Short words.", transcribe_ms: 5, backend: "daemon_gpu" };
      },
    });
    assert.equal(calls.length, 1, "short audio must stay a single request");
    assert.equal(calls[0].reqId, "short-piece");
    assert.ok(calls[0].durationMs > 19_000 && calls[0].durationMs <= 20_000);
    assert.equal(result.pieces, 1);
    assert.equal(result.text, "Short words.");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a recording with speech reaches the model whole, silence included", async () => {
  const dir = tempDir("whole-recording");
  const file = writeSyntheticWav(dir, "whole.wav", concatPcm([silencePcm(4), speechBurst(3), silencePcm(4)]));
  const calls = [];
  try {
    const result = await transcribeAudioFile(file, "whole-recording", {
      speakerGate: false,
      transcribe: async (wavPath) => {
        calls.push(wavDurationMs(wavPath));
        return { text: "Spoken words.", transcribe_ms: 5, backend: "daemon_gpu" };
      },
    });
    assert.equal(calls.length, 1);
    assert.ok(calls[0] > 10_900 && calls[0] <= 11_000, `model received ${calls[0]}ms of 11000ms`);
    assert.ok(result.audioSentMs > 10_900);
    assert.equal(result.text, "Spoken words.");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a silent recording reports no speech without calling the model", async () => {
  const dir = tempDir("whole-silence");
  const file = writeSyntheticWav(dir, "silence.wav", silencePcm(11));
  let called = false;
  try {
    const result = await transcribeAudioFile(file, "whole-silence", {
      speakerGate: false,
      transcribe: async () => { called = true; return { text: "invented", backend: "daemon_gpu" }; },
    });
    assert.equal(called, false);
    assert.equal(result.hasSpeech, false);
    assert.equal(result.backend, "vad_silence_filter");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a long recording is transcribed as bounded pieces joined in order", async () => {
  const dir = tempDir("piece-long");
  const file = writeSyntheticWav(dir, "long.wav", flatTone(100));
  const texts = ["Alpha.", "Beta.", "Gamma."];
  const durations = [];
  let call = 0;
  try {
    const result = await transcribeAudioFile(file, "three", {
      speakerGate: false,
      transcribe: async (wavPath, reqId) => {
        assert.equal(reqId, `three-p${call}`, "pieces run sequentially with indexed ids");
        durations.push(wavDurationMs(wavPath));
        return { text: texts[call++], transcribe_ms: 10, backend: "daemon_gpu" };
      },
    });

    assert.equal(call, 3);
    assert.equal(result.pieces, 3);
    assert.equal(result.text, "Alpha. Beta. Gamma.");
    assert.deepEqual(result.pieceBackends, ["daemon_gpu", "daemon_gpu", "daemon_gpu"]);
    for (const [index, durationMs] of durations.entries()) {
      assert.ok(durationMs <= 45_000 + 1, `piece ${index} is ${durationMs}ms long`);
    }
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a forced seam drops the words the overlapping pieces repeat", async () => {
  const dir = tempDir("piece-dedup");
  // 50 s of flat tone: one forced 45 s cut, then a 43.5 s overlapping tail.
  const file = writeSyntheticWav(dir, "forced.wav", flatTone(50));
  const texts = ["Alpha beta gamma delta", "delta epsilon zeta"];
  try {
    const result = await transcribeAudioFile(file, "dedup", {
      speakerGate: false,
      transcribe: async () => ({
        text: texts.shift(),
        transcribe_ms: 1,
        backend: "daemon_gpu",
      }),
    });
    assert.equal(result.pieces, 2);
    assert.equal(result.text, "Alpha beta gamma delta epsilon zeta");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a pause seam keeps a word that genuinely repeats", async () => {
  const dir = tempDir("piece-pause");
  const pcm = concatPcm([speechBurst(26.8), silencePcm(0.4), speechBurst(33)]);
  const file = writeSyntheticWav(dir, "pause.wav", pcm);
  const texts = ["alpha omega", "omega beta"];
  try {
    const result = await transcribeAudioFile(file, "pause", {
      speakerGate: false,
      transcribe: async () => ({
        text: texts.shift(),
        transcribe_ms: 1,
        backend: "daemon_gpu",
      }),
    });
    assert.equal(result.pieces, 2);
    assert.equal(result.text, "alpha omega omega beta", "pause seams are never deduplicated");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("one piece falling back does not resend the pieces that succeeded", async () => {
  const dir = tempDir("piece-fallback");
  const file = writeSyntheticWav(dir, "fallback.wav", flatTone(100));
  const socketCalls = [];
  const handyCalls = [];
  try {
    const result = await transcribeAudioFile(file, "fallback", {
      speakerGate: false,
      transcribe: (wavPath, reqId) => defaultTranscribe(wavPath, reqId, {
        tiers: [
          { backend: "daemon_gpu", kind: "socket" },
          { backend: "handy_direct_cpu", kind: "handy", deviceIndex: 2 },
        ],
        socketQuery: async (_wav, id) => {
          socketCalls.push(id);
          if (id === "fallback-p1") throw new Error("daemon rejected this piece");
          return { text: `daemon ${id}`, transcribe_ms: 3 };
        },
        handyQuery: async () => {
          handyCalls.push("cpu");
          return { text: "fallback p1", transcribe_ms: 4 };
        },
      }),
    });

    assert.deepEqual(socketCalls, ["fallback-p0", "fallback-p1", "fallback-p2"]);
    assert.equal(handyCalls.length, 1, "only the failed piece reaches the next tier");
    assert.equal(result.pieces, 3);
    assert.deepEqual(result.pieceBackends, ["daemon_gpu", "handy_direct_cpu", "daemon_gpu"]);
    assert.equal(result.backend, "handy_direct_cpu", "the degraded tier is not hidden");
    assert.equal(result.text, "daemon fallback-p0 fallback p1 daemon fallback-p2");
    assert.deepEqual(
      result.backendAttempts.map((attempt) => ({ piece: attempt.piece, backend: attempt.backend })),
      [{ piece: 1, backend: "daemon_gpu" }],
    );
    assert.match(result.backendAttempts[0].error, /daemon rejected this piece/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a piece that exhausts every tier fails the whole transcription with its index", async () => {
  const dir = tempDir("piece-fail");
  const file = writeSyntheticWav(dir, "fail.wav", flatTone(100));
  const calls = [];
  const failure = new Error("All Whisper backends failed: daemon_gpu (down)");
  failure.attempts = [{ backend: "daemon_gpu", error: "down" }];
  try {
    await assert.rejects(
      transcribeAudioFile(file, "fail", {
        speakerGate: false,
        transcribe: async (_wavPath, reqId) => {
          calls.push(reqId);
          if (reqId === "fail-p1") throw failure;
          return { text: "ok", transcribe_ms: 1, backend: "daemon_gpu" };
        },
      }),
      (error) => {
        assert.equal(error.pieceIndex, 1);
        assert.equal(error.message, failure.message);
        assert.deepEqual(error.attempts, failure.attempts);
        return true;
      },
    );
    assert.deepEqual(calls, ["fail-p0", "fail-p1"], "later pieces are not attempted");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
