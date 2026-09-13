import { spawn } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { prepareAudioForTranscription } from "/home/qqp/projects/qq-dictation/src/recognizer.mjs";
import { decodeWav, encodePcm16Wav } from "./wav.mjs";
import { gateAudio, summarizeGate } from "./gate.mjs";
import { createSpeakerEmbedder } from "./speaker.mjs";
import { loadVoiceProfile, profileCompatibility } from "./profile.mjs";

const DEFAULT_SOCKET_PATH = "/tmp/orca_whisper.sock";
const DEFAULT_HANDY_BIN = "/home/qqp/.local/bin/handy";

/**
 * Hard cap for a single warm-daemon socket exchange: 15 seconds. The daemon is
 * expected to answer in milliseconds; anything slower means it is wedged and
 * the pipeline must fall back to spawning `handy` directly instead of hanging
 * the request (the previous cap was 10 minutes).
 */
export const WHISPER_SOCKET_TIMEOUT_MS = 15_000;

/** Upper bound for one `handy --transcribe-file` run before it is killed. */
export const HANDY_TIMEOUT_MS = 600_000;

/**
 * Device registry indices reported by `handy --list-devices` on this host:
 *   0 = AMD Radeon 780M (integrated), 1 = NVIDIA RTX A2000, 2 = CPU.
 * Overridable per host so the cascade is not hard-wired to one machine.
 */
export const HANDY_DEVICE_INDEX = Object.freeze({
  igpu: 0,
  gpu: 1,
  cpu: 2,
});

function envString(name, fallback) {
  const value = process.env[name];
  return typeof value === "string" && value.trim() ? value.trim() : fallback;
}

function envNumber(name, fallback) {
  const parsed = Number.parseInt(process.env[name] ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function envIndex(name, fallback) {
  const parsed = Number.parseInt(process.env[name] ?? "", 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function socketPath() {
  return envString("WHISPER_SOCKET", DEFAULT_SOCKET_PATH);
}

function handyBin() {
  return envString("HANDY_BIN", DEFAULT_HANDY_BIN);
}

/**
 * Ordered backend cascade used by {@link defaultTranscribe}. Each tier fails
 * over to the next one, so a wedged socket daemon or a GPU that cannot allocate
 * VRAM degrades to the iGPU and finally to the CPU instead of hanging.
 */
export function transcribeTiers() {
  return [
    {
      backend: "daemon_gpu",
      kind: "socket",
      label: "warm Whisper daemon (RTX A2000)",
      socketPath: socketPath(),
      timeoutMs: envNumber("WHISPER_SOCKET_TIMEOUT_MS", WHISPER_SOCKET_TIMEOUT_MS),
    },
    {
      backend: "handy_direct_gpu",
      kind: "handy",
      label: "handy direct (RTX A2000)",
      deviceIndex: envIndex("HANDY_DEVICE_INDEX_GPU", HANDY_DEVICE_INDEX.gpu),
    },
    {
      backend: "handy_direct_igpu",
      kind: "handy",
      label: "handy direct (Radeon 780M)",
      deviceIndex: envIndex("HANDY_DEVICE_INDEX_IGPU", HANDY_DEVICE_INDEX.igpu),
    },
    {
      backend: "handy_direct_cpu",
      kind: "handy",
      label: "handy direct (CPU)",
      deviceIndex: envIndex("HANDY_DEVICE_INDEX_CPU", HANDY_DEVICE_INDEX.cpu),
    },
  ];
}

/**
 * Anti-hallucination filter to scrub Whisper silence hallucinations and trailing artifacts.
 */
export function cleanTranscript(text) {
  let s = (text || "").trim();
  if (!s) return "";

  // Exact silence hallucination phrases Whisper produces on room tone/silence
  const silencePhrases = [
    /^(thank you|thanks for watching|thank you for watching|thanks|bye|goodbye|see you next time|subtitles by.*|amara\.org.*|you)[\.!\?]?$/i,
    /^[\.\,\?\!\:\;\-\–\—\s]+$/
  ];
  for (const pat of silencePhrases) {
    if (pat.test(s)) return "";
  }

  // Trailing silence phrases attached to real speech (e.g. "meeting at 3. Thank you.")
  const trailingPat = /[\s,]+(thank you|thanks for watching|thank you for watching|bye|goodbye|see you next time)[\.!\?]?$/i;
  s = s.replace(trailingPat, "");

  // Trailing repetitive echo loops
  s = s.replace(/\b(\w+[\s\w]{2,20}?)\s+\1\s+\1\b/gi, "$1");

  return s.trim();
}

/**
 * Convert an input audio file (webm, m4a, mp4, ogg, wav) to a 16kHz mono WAV file.
 */
export function convertToWav(inputPath, outputPath) {
  return new Promise((resolve, reject) => {
    const ff = spawn("ffmpeg", [
      "-y",
      "-i", inputPath,
      "-ar", "16000",
      "-ac", "1",
      "-c:a", "pcm_s16le",
      outputPath
    ]);

    let stderr = "";
    ff.stderr.on("data", (chunk) => {
      stderr += chunk;
    });

    ff.on("close", (code) => {
      if (code === 0 && fs.existsSync(outputPath) && fs.statSync(outputPath).size > 44) {
        resolve(outputPath);
      } else {
        reject(new Error(`ffmpeg conversion failed (code ${code}): ${stderr}`));
      }
    });

    ff.on("error", reject);
  });
}

/**
 * Query the running Whisper daemon on Unix domain socket.
 *
 * Resolves with the daemon's JSON payload only on a clean 200 response with
 * `ok !== false`. Every other outcome - transport error, timeout, non-200
 * status, malformed JSON or an explicit `{ ok: false }` payload - rejects so
 * the caller can immediately fall back to the next backend instead of
 * returning empty text and leaving the client stuck on "Processing...".
 *
 * @param {string} wavPath
 * @param {string} reqId
 * @param {{socketPath?: string, timeoutMs?: number}} [options]
 */
export function queryWhisperSocket(wavPath, reqId, options = {}) {
  const targetSocket = options.socketPath || socketPath();
  const timeoutMs = Number.isFinite(options.timeoutMs)
    ? options.timeoutMs
    : envNumber("WHISPER_SOCKET_TIMEOUT_MS", WHISPER_SOCKET_TIMEOUT_MS);
  const maxBodyBytes = 1024 * 1024;

  return new Promise((resolve, reject) => {
    const postData = JSON.stringify({ wav: wavPath, id: reqId });
    let settled = false;

    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error instanceof Error ? error : new Error(String(error)));
    };
    const succeed = (value) => {
      if (settled) return;
      settled = true;
      resolve(value);
    };

    const req = http.request({
      socketPath: targetSocket,
      path: "/",
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(postData)
      },
      timeout: timeoutMs
    }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        if (body.length < maxBodyBytes) body += chunk;
      });
      res.on("error", fail);
      res.on("aborted", () => fail(new Error("Whisper socket response aborted")));
      res.on("end", () => {
        if (res.statusCode !== 200) {
          fail(new Error(`Whisper daemon HTTP ${res.statusCode}: ${body.trim().slice(0, 200) || "no body"}`));
          return;
        }
        let parsed;
        try {
          parsed = JSON.parse(body);
        } catch {
          fail(new Error(`Invalid JSON from whisper daemon: ${body.trim().slice(0, 200)}`));
          return;
        }
        if (parsed && typeof parsed === "object" && parsed.ok === false) {
          const detail = parsed.error || parsed.message || JSON.stringify(parsed).slice(0, 200);
          fail(new Error(`Whisper daemon reported failure: ${detail}`));
          return;
        }
        succeed(parsed);
      });
    });

    req.on("error", fail);
    req.on("timeout", () => {
      req.destroy();
      fail(new Error(`Whisper socket timeout after ${timeoutMs}ms`));
    });

    try {
      req.write(postData);
      req.end();
    } catch (error) {
      // Broken pipe / already-destroyed socket: fail over immediately.
      fail(error);
    }
  });
}

/**
 * Fallback to executing handy directly if the socket daemon is unavailable.
 *
 * @param {string} wavPath
 * @param {{deviceIndex?: number, bin?: string, timeoutMs?: number, env?: object}} [options]
 */
export function queryHandyDirect(wavPath, options = {}) {
  const bin = options.bin || handyBin();
  const deviceIndex = Number.isFinite(options.deviceIndex)
    ? options.deviceIndex
    : envIndex("HANDY_DEVICE_INDEX_GPU", HANDY_DEVICE_INDEX.gpu);
  const timeoutMs = Number.isFinite(options.timeoutMs)
    ? options.timeoutMs
    : envNumber("HANDY_TIMEOUT_MS", HANDY_TIMEOUT_MS);

  return new Promise((resolve, reject) => {
    const start = Date.now();
    const child = spawn(bin, [
      "--transcribe-file", wavPath,
      "--model", "turbo",
      "--device-index", String(deviceIndex),
      "--json"
    ], {
      env: {
        ...process.env,
        DISPLAY: process.env.DISPLAY || ":0",
        GDK_BACKEND: process.env.GDK_BACKEND || "x11",
        MESA_VK_DEVICE_SELECT: process.env.MESA_VK_DEVICE_SELECT || "1002:1900",
        ...(options.env || {}),
      }
    });

    let stdout = "";
    let stderr = "";
    let settled = false;
    let timer = null;

    child.stdout.on("data", chunk => stdout += chunk);
    child.stderr.on("data", chunk => stderr += chunk);

    const fail = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(error instanceof Error ? error : new Error(String(error)));
    };

    timer = setTimeout(() => {
      try { child.kill("SIGTERM"); } catch {}
      // A killed process can leave grandchildren holding the stdio pipes open,
      // so surface the timeout now instead of waiting for 'close'.
      try { child.stdout?.destroy(); } catch {}
      try { child.stderr?.destroy(); } catch {}
      fail(new Error(`handy timed out after ${timeoutMs}ms (device ${deviceIndex})`));
    }, timeoutMs);
    timer.unref?.();

    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const transcribeMs = Date.now() - start;
      if (code === 0) {
        try {
          const parsed = JSON.parse(stdout.trim());
          resolve({ ok: true, text: parsed.text || "", transcribe_ms: transcribeMs });
        } catch {
          const match = stdout.match(/^text:\s*(.*)$/m);
          resolve({ ok: true, text: match ? match[1] : stdout.trim(), transcribe_ms: transcribeMs });
        }
      } else {
        reject(new Error(`handy process failed (code ${code}, device ${deviceIndex}): ${stderr.trim().slice(0, 300)}`));
      }
    });

    child.on("error", fail);
  });
}

/**
 * Default Whisper backend: warm daemon over the unix socket, then `handy`
 * spawned directly on the RTX A2000, then the Radeon 780M iGPU, then the CPU.
 * Each tier starts only after the previous one failed or was rejected.
 *
 * @param {string} wavPath
 * @param {string} reqId
 * @param {{tiers?: object[], socketQuery?: Function, handyQuery?: Function}} [options]
 */
export async function defaultTranscribe(wavPath, reqId, options = {}) {
  const tiers = options.tiers || transcribeTiers();
  const socketQuery = options.socketQuery || queryWhisperSocket;
  const handyQuery = options.handyQuery || queryHandyDirect;
  const attempts = [];

  for (const tier of tiers) {
    try {
      const resp = tier.kind === "socket"
        ? await socketQuery(wavPath, reqId, { socketPath: tier.socketPath, timeoutMs: tier.timeoutMs })
        : await handyQuery(wavPath, { deviceIndex: tier.deviceIndex, bin: tier.bin, timeoutMs: tier.timeoutMs });

      return {
        text: typeof resp?.text === "string" ? resp.text : "",
        transcribe_ms: resp?.transcribe_ms,
        backend: tier.backend,
        attempts,
      };
    } catch (error) {
      const message = error?.message || String(error);
      attempts.push({ backend: tier.backend, error: message });
      console.warn(`[transcriber] ${tier.backend} unavailable: ${message}`);
    }
  }

  const failure = new Error(
    `All Whisper backends failed: ${attempts.map((a) => `${a.backend} (${a.error})`).join(" | ")}`,
  );
  failure.attempts = attempts;
  throw failure;
}

/**
 * Full end-to-end transcription of an audio file path.
 *
 * When a voiceprint is enrolled, each VAD speech segment is verified against
 * the enrolled gallery and only the segments that match the user are sent to
 * Whisper. With no profile (or an incompatible/unavailable embedder) the
 * pipeline behaves exactly as before - a graceful, regression-free fallback.
 *
 * @param {string} rawAudioPath
 * @param {string} reqId
 * @param {object} [options]
 * @param {(wavPath: string, reqId: string) => Promise<{text: string, transcribe_ms?: number, backend?: string}>} [options.transcribe]
 * @param {boolean} [options.speakerGate] force the gate on/off
 * @param {object|null} [options.profile] explicit profile (bypasses the database)
 * @param {object} [options.embedder] explicit embedder instance
 */
export async function transcribeAudioFile(rawAudioPath, reqId = `req-${Date.now()}`, options = {}) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-"));
  const rawWavPath = path.join(tmpDir, "converted.wav");
  const filteredWavPath = path.join(tmpDir, "filtered.wav");
  const gateRequested =
    options.speakerGate ?? process.env.VOICE_VAULT_SPEAKER_GATE !== "off";

  try {
    const startTime = Date.now();

    // 1. Convert to 16kHz mono WAV
    await convertToWav(rawAudioPath, rawWavPath);

    // 2. Anti-Hallucination Layer 1: VAD Silence Filter
    const wavBytes = fs.readFileSync(rawWavPath);
    const prepared = prepareAudioForTranscription(wavBytes);

    if (prepared.hasSpeech === false || prepared.wavBytes.length <= 44) {
      return {
        ok: true,
        text: "",
        hasSpeech: false,
        durationMs: 0,
        transcribeMs: Date.now() - startTime,
        backend: "vad_silence_filter",
        gate: { enabled: false, reason: "no_speech" }
      };
    }

    const inputDurationMs = Math.round((prepared.wavBytes.length - 44) / 32);

    // 3. Anti-Hallucination Layer 2: target-speaker voiceprint gate
    let audioToTranscribe = prepared.wavBytes;
    let gate = { enabled: false, reason: gateRequested ? "not_enrolled" : "disabled" };

    if (gateRequested) {
      const profile = Object.prototype.hasOwnProperty.call(options, "profile")
        ? options.profile
        : loadVoiceProfile();

      if (profile && profile.enrolled) {
        let embedder = options.embedder || null;
        try {
          if (!embedder) embedder = await createSpeakerEmbedder(options.embedderOptions || {});
        } catch (error) {
          embedder = null;
          gate = { enabled: false, reason: `embedder_unavailable:${error.message}` };
        }

        const compatibility = profileCompatibility(profile, embedder);
        if (embedder && !compatibility.compatible) {
          gate = { enabled: false, reason: compatibility.reason };
        } else if (embedder) {
          const decoded = decodeWav(prepared.wavBytes);
          if (!decoded) {
            gate = { enabled: false, reason: "undecodable_audio" };
          } else {
            const result = await gateAudio({
              pcm: decoded.samples,
              sampleRate: decoded.sampleRate,
              profile,
              embedder,
              vad: options.gate?.vad || {},
              decision: options.gate?.decision || {},
            });
            gate = {
              ...result,
              profile: {
                modelId: profile.modelId,
                backend: profile.backend,
                dim: profile.dim,
                sampleCount: profile.sampleCount,
                threshold: profile.threshold,
                mu: profile.mu,
                sigma: profile.sigma,
              },
            };

            if (result.kept === 0) {
              return {
                ok: true,
                text: "",
                hasSpeech: false,
                rejected: true,
                durationMs: inputDurationMs,
                transcribeMs: Date.now() - startTime,
                backend: "speaker_gate_rejected",
                gate: summarizeGate(gate),
              };
            }

            audioToTranscribe = encodePcm16Wav(result.acceptedPcm, decoded.sampleRate);
          }
        }
      }
    }

    fs.writeFileSync(filteredWavPath, audioToTranscribe);

    // 4. Transcribe via the backend cascade (warm daemon -> dGPU -> iGPU -> CPU)
    const transcribe = options.transcribe || defaultTranscribe;
    const resp = await transcribe(filteredWavPath, reqId);

    const rawText = (resp && typeof resp.text === "string") ? resp.text : "";
    const cleanText = cleanTranscript(rawText);

    return {
      ok: true,
      text: cleanText,
      hasSpeech: cleanText.length > 0,
      rejected: false,
      durationMs: inputDurationMs,
      audioSentMs: Math.round((audioToTranscribe.length - 44) / 32),
      transcribeMs: resp?.transcribe_ms || (Date.now() - startTime),
      backend: resp?.backend || "daemon_gpu",
      backendAttempts: resp?.attempts,
      gate: summarizeGate(gate),
    };
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}
