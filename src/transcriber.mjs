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

const SOCKET_PATH = process.env.WHISPER_SOCKET || "/tmp/orca_whisper.sock";
const HANDY_BIN = process.env.HANDY_BIN || "/home/qqp/.local/bin/handy";

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
 */
function queryWhisperSocket(wavPath, reqId) {
  return new Promise((resolve, reject) => {
    const postData = JSON.stringify({ wav: wavPath, id: reqId });
    const req = http.request({
      socketPath: SOCKET_PATH,
      path: "/",
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(postData)
      },
      timeout: 600000 // 10 minutes for arbitrarily long recordings
    }, (res) => {
      let body = "";
      res.on("data", chunk => body += chunk);
      res.on("end", () => {
        try {
          const parsed = JSON.parse(body);
          resolve(parsed);
        } catch (e) {
          reject(new Error(`Invalid JSON from whisper daemon: ${body}`));
        }
      });
    });

    req.on("error", reject);
    req.on("timeout", () => {
      req.destroy();
      reject(new Error("Whisper socket timeout"));
    });

    req.write(postData);
    req.end();
  });
}

/**
 * Fallback to executing handy directly if socket is down.
 */
function queryHandyDirect(wavPath) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const child = spawn(HANDY_BIN, [
      "--transcribe-file", wavPath,
      "--model", "turbo",
      "--device-index", "1",
      "--json"
    ], {
      env: {
        ...process.env,
        DISPLAY: process.env.DISPLAY || ":0",
        GDK_BACKEND: process.env.GDK_BACKEND || "x11",
        MESA_VK_DEVICE_SELECT: process.env.MESA_VK_DEVICE_SELECT || "1002:1900",
      }
    });

    let stdout = "";
    let stderr = "";
    child.stdout.on("data", chunk => stdout += chunk);
    child.stderr.on("data", chunk => stderr += chunk);

    child.on("close", (code) => {
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
        reject(new Error(`handy process failed (code ${code}): ${stderr}`));
      }
    });

    child.on("error", reject);
  });
}

/**
 * Default Whisper backend: warm daemon over the unix socket, handy as fallback.
 */
async function defaultTranscribe(wavPath, reqId) {
  try {
    const resp = await queryWhisperSocket(wavPath, reqId);
    return {
      text: typeof resp?.text === "string" ? resp.text : "",
      transcribe_ms: resp?.transcribe_ms,
      backend: "daemon_gpu",
    };
  } catch {
    const resp = await queryHandyDirect(wavPath);
    return {
      text: typeof resp?.text === "string" ? resp.text : "",
      transcribe_ms: resp?.transcribe_ms,
      backend: "handy_direct_gpu",
    };
  }
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

    // 4. Transcribe via warm Whisper Daemon on RTX A2000
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
      gate: summarizeGate(gate),
    };
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}
