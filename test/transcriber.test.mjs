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
} = await import("../src/transcriber.mjs");

const ENV_KEYS = [
  "WHISPER_SOCKET",
  "WHISPER_SOCKET_TIMEOUT_MS",
  "HANDY_BIN",
  "HANDY_TIMEOUT_MS",
  "HANDY_DEVICE_INDEX_GPU",
  "HANDY_DEVICE_INDEX_IGPU",
  "HANDY_DEVICE_INDEX_CPU",
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
