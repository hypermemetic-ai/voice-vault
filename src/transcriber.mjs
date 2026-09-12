import { spawn, spawnSync } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { prepareAudioForTranscription } from "/home/qqp/projects/qq-dictation/src/recognizer.mjs";

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
 * Full end-to-end transcription of an audio file path.
 */
export async function transcribeAudioFile(rawAudioPath, reqId = `req-${Date.now()}`) {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-"));
  const rawWavPath = path.join(tmpDir, "converted.wav");
  const filteredWavPath = path.join(tmpDir, "filtered.wav");

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
        backend: "vad_silence_filter"
      };
    }

    // Write the VAD-trimmed audio
    fs.writeFileSync(filteredWavPath, prepared.wavBytes);

    // 3. Transcribe via warm Whisper Daemon on RTX A2000
    let resp;
    let backend = "daemon_gpu";
    try {
      resp = await queryWhisperSocket(filteredWavPath, reqId);
    } catch (daemonErr) {
      // Fallback to handy process
      resp = await queryHandyDirect(filteredWavPath);
      backend = "handy_direct_gpu";
    }

    const rawText = (resp && typeof resp.text === "string") ? resp.text : "";
    const cleanText = cleanTranscript(rawText);

    return {
      ok: true,
      text: cleanText,
      hasSpeech: cleanText.length > 0,
      transcribeMs: resp.transcribe_ms || (Date.now() - startTime),
      backend
    };
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}
