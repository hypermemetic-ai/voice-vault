import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import os from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import * as recordingDb from "./db.mjs";
import { transcribeAudioFile, convertToWav } from "./transcriber.mjs";
import { decodeWav, encodePcm16Wav } from "./wav.mjs";
import {
  MAX_ENROLL_SAMPLES,
  MIN_ENROLL_SAMPLE_MS,
  RECOMMENDED_ENROLL_SAMPLES,
  clearVoiceProfile,
  describeProfile,
  enrollVoice,
  loadVoiceProfile,
  verifyAgainstProfile,
} from "./profile.mjs";
import {
  DEFAULT_SPEAKER_MODEL,
  DEFAULT_CALIBRATION,
  DEFAULT_DECISION,
  SPEAKER_MODELS,
  createSpeakerEmbedder,
  modelPathFor,
} from "./speaker.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, "..", "public");
const DEFAULT_RAW_DIR = process.env.VOICE_VAULT_STORAGE || "/home/qqp/recordings/voice-vault/raw";
const PORT = parseInt(process.env.PORT || "3005", 10);
const HOST = process.env.HOST || "0.0.0.0";
const MAX_UPLOAD = 100 * 1024 * 1024; // 100 MB
const MAX_PROFILE_UPLOAD = 60 * 1024 * 1024; // 60 MB for multi-clip enrollment

function parseMultipartFormData(buffer, boundary) {
  const boundaryBuffer = Buffer.from(`--${boundary}`);
  let start = 0;
  const parts = [];

  while ((start = buffer.indexOf(boundaryBuffer, start)) !== -1) {
    start += boundaryBuffer.length;
    if (buffer.slice(start, start + 2).toString() === "--") break; // End of parts
    if (buffer.slice(start, start + 2).toString() === "\r\n") start += 2;

    const nextBoundary = buffer.indexOf(boundaryBuffer, start);
    if (nextBoundary === -1) break;

    const partBuffer = buffer.slice(start, nextBoundary - 2); // Exclude \r\n
    const headerEnd = partBuffer.indexOf("\r\n\r\n");
    if (headerEnd !== -1) {
      const headerStr = partBuffer.slice(0, headerEnd).toString("utf8");
      const content = partBuffer.slice(headerEnd + 4);

      const nameMatch = headerStr.match(/name="([^"]+)"/);
      const filenameMatch = headerStr.match(/filename="([^"]+)"/);
      const typeMatch = headerStr.match(/Content-Type:\s*([^\r\n]+)/i);

      parts.push({
        name: nameMatch ? nameMatch[1] : "",
        filename: filenameMatch ? filenameMatch[1] : "",
        contentType: typeMatch ? typeMatch[1].trim() : "application/octet-stream",
        data: content
      });
    }
    start = nextBoundary;
  }
  return parts;
}

const MIME_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".mjs": "application/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".apk": "application/vnd.android.package-archive",
  ".otf": "font/otf",
  ".ttf": "font/ttf",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".webm": "audio/webm",
  ".m4a": "audio/mp4",
  ".mp4": "audio/mp4",
  ".wav": "audio/wav"
};

function sendJson(res, status, payload) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
}

async function readBody(req, limit = MAX_UPLOAD) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > limit) {
      const error = new Error("Upload payload exceeds limit");
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function extensionFor(part) {
  if (part.filename) {
    const ext = path.extname(part.filename).toLowerCase();
    if ([".webm", ".m4a", ".mp4", ".wav", ".aac", ".ogg", ".mp3"].includes(ext)) return ext;
  }
  if (part.contentType.includes("mp4") || part.contentType.includes("m4a")) return ".m4a";
  if (part.contentType.includes("wav")) return ".wav";
  if (part.contentType.includes("ogg")) return ".ogg";
  if (part.contentType.includes("mpeg")) return ".mp3";
  return ".webm";
}

/**
 * Normalise any uploaded clip (webm/opus, m4a/aac, wav at any rate) into
 * 16 kHz mono PCM16 via ffmpeg. Returns null when the clip carries no audio.
 */
async function clipToPcm16(data, extension) {
  if (!data || data.length === 0) return null;
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-enroll-"));
  const rawPath = path.join(tmpDir, `input${extension || ".bin"}`);
  const wavPath = path.join(tmpDir, "converted.wav");
  try {
    fs.writeFileSync(rawPath, data);
    await convertToWav(rawPath, wavPath);
    const decoded = decodeWav(fs.readFileSync(wavPath));
    if (!decoded || decoded.samples.length === 0) return null;
    return decoded.samples;
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}

/**
 * Extract enrollment clips from either multipart/form-data (`sample`,
 * `samples`, `audio`, `clip`, `file` parts) or a JSON body with base64 audio.
 */
async function collectClips(req, body) {
  const contentType = req.headers["content-type"] || "";
  const clips = [];
  let modelId = null;
  let sampleRate = 16000;

  if (contentType.includes("multipart/form-data")) {
    const boundary = contentType.split("boundary=")[1]?.trim();
    if (!boundary) {
      throw Object.assign(new Error("Malformed multipart body: missing boundary"), { statusCode: 400 });
    }
    const parts = parseMultipartFormData(body, boundary);
    for (const part of parts) {
      if (/^(sample|samples|audio|clip|file|voice)/i.test(part.name)) {
        const pcm = await clipToPcm16(part.data, extensionFor(part));
        if (pcm) clips.push(pcm);
      } else if (part.name === "modelId") {
        modelId = part.data.toString("utf8").trim() || null;
      } else if (part.name === "sampleRate") {
        sampleRate = parseInt(part.data.toString("utf8"), 10) || sampleRate;
      }
    }
  } else {
    let payload;
    try {
      payload = JSON.parse(body.toString("utf8") || "{}");
    } catch {
      throw Object.assign(new Error("Expected multipart/form-data or a JSON body"), { statusCode: 400 });
    }
    modelId = payload.modelId || null;
    sampleRate = parseInt(payload.sampleRate || "16000", 10) || 16000;

    const entries = [];
    if (Array.isArray(payload.samples)) entries.push(...payload.samples);
    else if (Array.isArray(payload.clips)) entries.push(...payload.clips);
    if (payload.audioBase64) entries.push(payload.audioBase64);
    if (payload.wavBase64) entries.push({ wavBase64: payload.wavBase64 });
    if (payload.pcm16Base64) entries.push({ pcm16Base64: payload.pcm16Base64 });

    for (const entry of entries) {
      const item = typeof entry === "string" ? { audioBase64: entry } : entry || {};
      const wavBase64 = item.wavBase64 || (item.audioBase64 && item.format !== "pcm16" ? item.audioBase64 : null);
      if (wavBase64) {
        const pcm = await clipToPcm16(Buffer.from(wavBase64, "base64"), ".wav");
        if (pcm) clips.push(pcm);
        continue;
      }
      const pcmBase64 = item.pcm16Base64 || (item.format === "pcm16" ? item.audioBase64 : null);
      if (pcmBase64) {
        const raw = Buffer.from(pcmBase64, "base64");
        const pcm = await clipToPcm16(encodePcm16Wav(raw, parseInt(item.sampleRate || sampleRate, 10)), ".wav");
        if (pcm) clips.push(pcm);
      }
    }
  }

  return { clips, modelId, sampleRate };
}

/**
 * Build the HTTP server. Dependencies are injectable so tests can run the real
 * routing against stubbed transcription.
 */
export function createAppServer(options = {}) {
  const rawDir = options.rawDir || DEFAULT_RAW_DIR;
  const publicDir = options.publicDir || PUBLIC_DIR;
  fs.mkdirSync(rawDir, { recursive: true });
  const runTranscription = options.transcribeAudioFile || transcribeAudioFile;
  const store = options.recordingStore || recordingDb;
  const files = options.recordingFiles || fs;
  // One production server owns this store; a restart never starts inference.
  store.reconcileRecordings();
  const liveAttempts = new Set();
  const terminal = new Set(["transcribed", "no_speech", "speaker_rejected"]);
  const validId = id => typeof id === "string" && /^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(id);
  const ownedPath = rec => {
    if (!rec || !validId(rec.id) || !rec.raw_filename ||
        path.basename(rec.raw_filename) !== rec.raw_filename ||
        !rec.raw_filename.startsWith(`${rec.id}.`)) return null;
    const file = path.join(rawDir, rec.raw_filename);
    try { const stat = files.lstatSync(file); return stat.isFile() && !stat.isSymbolicLink() ? file : null; }
    catch { return null; }
  };
  const available = rec => { const file = ownedPath(rec); return Boolean(file && files.statSync(file).size > 0); };
  const category = error => {
    const code = String(error?.code || "");
    const message = String(error?.message || "");
    if (/ENOSPC|SQLITE_FULL/.test(code) || /database or disk is full|no space left/i.test(message)) return "storage_full";
    if (/SQLITE|EIO|EROFS|EACCES/.test(code)) return "storage_failure";
    if (/TIMEOUT|ETIMEDOUT/.test(code) || /timed? ?out/i.test(message)) return "timeout";
    if (/ffmpeg|convert|invalid audio/i.test(message)) return "invalid_audio";
    if (/backend|whisper|handy/i.test(message)) return "backend_failed";
    return "processing_failed";
  };
  const view = rec => ({ id: rec.id, created_at: rec.created_at, duration_ms: rec.duration_ms,
    transcript: rec.transcript, status: rec.status, error_category: rec.error_category,
    attempt_generation: rec.attempt_generation, audio_available: available(rec),
    audioUrl: `/api/audio/${rec.id}`, outcome: rec.outcome_json ? JSON.parse(rec.outcome_json) : null });
  const reconcileIdle = rec => {
    if (rec && ["processing", "intake", "pending"].includes(rec.status) && !liveAttempts.has(rec.id)) {
      return store.updateRecording(rec.id, "interrupted", "interrupted", available(rec));
    }
    return rec;
  };
  const respondExisting = (res, rec) => {
    if (terminal.has(rec.status)) {
      const outcome = rec.outcome_json ? JSON.parse(rec.outcome_json) : {
        ok: true, id: rec.id, text: rec.transcript, hasSpeech: rec.status === "transcribed",
        rejected: rec.status === "speaker_rejected", durationMs: rec.duration_ms,
        transcribeMs: rec.transcribe_ms, audioUrl: `/api/audio/${rec.id}` };
      sendJson(res, 200, outcome);
    } else sendJson(res, rec.status === "processing" || rec.status === "intake" ? 409 : 422,
      { ok: false, id: rec.id, status: rec.status, errorCategory: rec.error_category || "busy",
        audioAvailable: available(rec), saved: available(rec) });
  };
  const processRecording = async (res, rec) => {
    let claimed;
    try { claimed = store.claimRecording(rec.id); }
    catch (error) {
      sendJson(res, 500, { ok: false, id: rec.id, errorCategory: category(error), saved: false, audioAvailable: available(rec) }); return;
    }
    if (!claimed) { respondExisting(res, store.getRecording(rec.id)); return; }
    liveAttempts.add(claimed.id);
    try {
      const result = await runTranscription(ownedPath(claimed), claimed.id, options.transcribeOptions);
      const status = result.rejected ? "speaker_rejected" : result.hasSpeech ? "transcribed" : "no_speech";
      const outcome = { ok: true, id: claimed.id, text: result.text, hasSpeech: result.hasSpeech,
        rejected: Boolean(result.rejected), durationMs: claimed.duration_ms,
        audioSentMs: result.audioSentMs, transcribeMs: result.transcribeMs,
        audioUrl: `/api/audio/${claimed.id}`, backend: result.backend, backendAttempts: result.backendAttempts,
        rescue: result.rescue ?? null, gate: result.gate };
      if (!store.finishRecording(claimed.id, claimed.attempt_generation, status, outcome)) throw new Error("Metadata commit failed");
      sendJson(res, 200, outcome);
    } catch (error) {
      const errorCategory = category(error);
      let metadataSaved = false;
      try { metadataSaved = Boolean(store.finishRecording(claimed.id, claimed.attempt_generation, "failed", null, errorCategory)); } catch {}
      console.error(`Recording processing failed: ${errorCategory}`);
      sendJson(res, 500, { ok: false, id: claimed.id, status: "failed", errorCategory,
        saved: metadataSaved && available(claimed), audioAvailable: available(claimed) });
    } finally { liveAttempts.delete(claimed.id); }
  };

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname;

    // CORS headers for local LAN/Tailscale access
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type, Content-Length, X-Duration-Ms, X-Device, X-Recording-Id");

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    console.log(`[${new Date().toISOString()}] ${req.method} ${pathname}`);

    try {
      // -------------------------------------------------------------
      // API: Voice profile status
      // -------------------------------------------------------------
      if (req.method === "GET" && pathname === "/api/profile/status") {
        const profile = loadVoiceProfile();
        const spec = SPEAKER_MODELS[DEFAULT_SPEAKER_MODEL];
        const modelPath = modelPathFor(DEFAULT_SPEAKER_MODEL);
        sendJson(res, 200, describeProfile(profile, {
          configuration: {
            gateEnabled: process.env.VOICE_VAULT_SPEAKER_GATE !== "off",
            strictness: DEFAULT_CALIBRATION.strictness,
            sigmaFloor: DEFAULT_CALIBRATION.sigmaFloor,
            shortUtteranceMs: DEFAULT_DECISION.shortUtteranceMs,
            shortUtteranceBoostSigmas: DEFAULT_DECISION.shortUtteranceBoostSigmas,
            minAbsoluteScore: DEFAULT_DECISION.minAbsoluteScore,
            minEnrollSampleMs: MIN_ENROLL_SAMPLE_MS,
            recommendedSamples: RECOMMENDED_ENROLL_SAMPLES,
            maxSamples: MAX_ENROLL_SAMPLES,
          },
          models: {
            default: DEFAULT_SPEAKER_MODEL,
            defaultLabel: spec.label,
            defaultDim: spec.outputDim,
            installed: Object.values(SPEAKER_MODELS)
              .filter((model) => fs.existsSync(path.join(path.dirname(modelPath), model.file)))
              .map((model) => ({ id: model.id, dim: model.outputDim, label: model.label })),
            available: Object.values(SPEAKER_MODELS).map((model) => ({
              id: model.id,
              dim: model.outputDim,
              label: model.label,
            })),
          },
        }));
        return;
      }

      // -------------------------------------------------------------
      // API: Enroll voice profile (multi-sample)
      // -------------------------------------------------------------
      if (req.method === "POST" && pathname === "/api/profile/enroll") {
        const body = await readBody(req, MAX_PROFILE_UPLOAD);
        if (body.length === 0) {
          sendJson(res, 400, { ok: false, error: "Empty enrollment payload" });
          return;
        }
        const { clips, modelId } = await collectClips(req, body);
        if (clips.length === 0) {
          sendJson(res, 400, {
            ok: false,
            error: "No decodable audio clips found. Send 3 clips of ~5 s as 'sample' parts or base64 samples.",
          });
          return;
        }

        const embedder = await createSpeakerEmbedder(
          modelId ? { modelId } : options.embedderOptions || {},
        );
        const result = await enrollVoice(clips, { embedder, modelId: embedder.modelId });
        const profile = result.profile;
        sendJson(res, 200, describeProfile(profile, {
          ok: true,
          enrolled: true,
          justEnrolled: true,
          clipsReceived: clips.length,
          clipsUsed: result.durationsMs.length,
          durationsMs: result.durationsMs,
          skippedClips: result.tooShort,
          embedMs: result.embedMs,
          backend: result.embedder.backend,
          embedder: {
            backend: result.embedder.backend,
            backendFamily: result.embedder.backendFamily,
            dim: result.embedder.dim,
            provider: result.embedder.provider,
            fbank: result.embedder.fbank,
            modelId: result.embedder.modelId,
            modelLabel: result.embedder.modelLabel,
          },
          calibration: result.calibration,
        }));
        return;
      }

      // -------------------------------------------------------------
      // API: Verify a clip against the enrolled profile
      // -------------------------------------------------------------
      if (req.method === "POST" && pathname === "/api/profile/verify") {
        const profile = loadVoiceProfile();
        if (!profile) {
          sendJson(res, 409, { ok: false, error: "No voice profile enrolled" });
          return;
        }
        const body = await readBody(req, MAX_PROFILE_UPLOAD);
        const { clips } = await collectClips(req, body);
        if (clips.length === 0) {
          sendJson(res, 400, { ok: false, error: "No decodable audio clip found" });
          return;
        }
        const embedder = options.embedder || (await createSpeakerEmbedder(options.embedderOptions || {}));
        const clip = clips[0];
        const durationMs = (clip.length / 16000) * 1000;
        const started = Date.now();
        const { embedding, backend } = await embedder.embed(clip, 16000);
        const verdict = verifyAgainstProfile(embedding, durationMs, profile);
        sendJson(res, 200, {
          ok: true,
          ...verdict,
          durationMs: Number(durationMs.toFixed(1)),
          embedMs: Date.now() - started,
          backend,
          profile: {
            modelId: profile.modelId,
            dim: profile.dim,
            threshold: profile.threshold,
            mu: profile.mu,
            sigma: profile.sigma,
            sampleCount: profile.sampleCount,
          },
        });
        return;
      }

      // -------------------------------------------------------------
      // API: Reset voice profile
      // -------------------------------------------------------------
      if (
        (req.method === "DELETE" && (pathname === "/api/profile" || pathname === "/api/profile/enroll")) ||
        (req.method === "POST" && pathname === "/api/profile/reset")
      ) {
        clearVoiceProfile();
        sendJson(res, 200, { ok: true, enrolled: false });
        return;
      }

      // -------------------------------------------------------------
      // API: Transcribe Audio
      // -------------------------------------------------------------
      if (req.method === "POST" && pathname === "/api/transcribe") {
        const bodyBuffer = await readBody(req, MAX_UPLOAD);
        if (bodyBuffer.length === 0) {
          sendJson(res, 400, { ok: false, error: "Empty audio payload" });
          return;
        }

        const contentType = req.headers["content-type"] || "";
        let audioData = null;
        let extension = ".webm";
        let clientDevice = req.headers["x-device"] || "unknown";
        let durationMs = parseInt(req.headers["x-duration-ms"] || "0", 10);

        if (contentType.includes("multipart/form-data")) {
          const boundary = contentType.split("boundary=")[1]?.trim();
          if (boundary) {
            const parts = parseMultipartFormData(bodyBuffer, boundary);
            for (const part of parts) {
              if (part.name === "audio" || part.name === "file") {
                audioData = part.data;
                extension = extensionFor(part);
              } else if (part.name === "durationMs") {
                durationMs = parseInt(part.data.toString(), 10) || durationMs;
              } else if (part.name === "device") {
                clientDevice = part.data.toString() || clientDevice;
              }
            }
          }
        } else {
          // Raw body upload
          audioData = bodyBuffer;
          if (contentType.includes("audio/mp4") || contentType.includes("audio/m4a")) {
            extension = ".m4a";
          } else if (contentType.includes("audio/wav")) {
            extension = ".wav";
          } else if (contentType.includes("audio/aac")) {
            extension = ".aac";
          }
        }

        if (!audioData || audioData.length === 0) {
          sendJson(res, 400, { ok: false, error: "No valid audio data found in request" });
          return;
        }

        const suppliedId = req.headers["x-recording-id"];
        if (suppliedId != null && !validId(suppliedId)) {
          sendJson(res, 400, { ok: false, errorCategory: "invalid_identity" }); return;
        }
        const id = suppliedId || crypto.randomUUID();
        const fingerprint = crypto.createHash("sha256").update(audioData).digest("hex");
        const existing = reconcileIdle(store.getRecording(id));
        if (existing) {
          const previousFingerprint = existing.fingerprint || (available(existing)
            ? crypto.createHash("sha256").update(files.readFileSync(ownedPath(existing))).digest("hex") : null);
          if (!previousFingerprint || previousFingerprint !== fingerprint) {
            sendJson(res, 409, { ok: false, id, errorCategory: "identity_conflict" }); return;
          }
          if (available(existing) || terminal.has(existing.status) || liveAttempts.has(id)) {
            respondExisting(res, existing); return;
          }
          // No accepted server copy: the same local bytes may repair intake using the same row.
        }
        const rawFilename = existing ? existing.raw_filename : `${id}${extension}`;
        if (!rawFilename || path.basename(rawFilename) !== rawFilename || !rawFilename.startsWith(`${id}.`)) {
          sendJson(res, 409, { ok: false, id, errorCategory: "identity_conflict" }); return;
        }
        let rec;
        try {
          rec = existing ? store.updateRecording(id, "intake", "", false) : store.saveRecording({ id, durationMs: Math.max(0, durationMs || 0), clientDevice,
            rawFilename, status: "intake", fingerprint, audioAvailable: false });
          const file = path.join(rawDir, rawFilename);
          const temporary = `${file}.part`;
          let fd, createdTemporary = false;
          try {
            // A crash may leave only this identity's incomplete intake staging file.
            if (existing && files.existsSync(temporary)) {
              const stale = files.lstatSync(temporary);
              if (!stale.isFile() || stale.isSymbolicLink()) throw Object.assign(new Error("Unsafe intake staging"), { code: "EACCES" });
              files.unlinkSync(temporary);
            }
            fd = files.openSync(temporary, "wx", 0o600); createdTemporary = true;
            files.writeFileSync(fd, audioData);
            files.fsyncSync(fd);
            files.closeSync(fd); fd = null;
            files.renameSync(temporary, file);
            const directory = files.openSync(rawDir, "r");
            try { files.fsyncSync(directory); } finally { files.closeSync(directory); }
          } finally {
            if (fd != null) files.closeSync(fd);
            if (createdTemporary) { try { files.unlinkSync(temporary); } catch {} }
          }
          rec = store.updateRecording(id, "pending", "", true);
        } catch (error) {
          const errorCategory = category(error);
          let metadataSaved = false;
          try { if (rec) metadataSaved = Boolean(store.updateRecording(id, "failed", errorCategory, available(rec))); } catch {}
          sendJson(res, 500, { ok: false, id, status: "failed", errorCategory,
            saved: false, audioAvailable: Boolean(rec && available(rec)), metadataSaved }); return;
        }
        await processRecording(res, rec);
        return;
      }

      const recordingMatch = pathname.match(/^\/api\/recording\/([^/]+)(\/transcribe)?$/);
      if (recordingMatch && ["GET", "POST", "DELETE"].includes(req.method)) {
        const id = recordingMatch[1];
        if (!validId(id)) { sendJson(res, 400, { ok: false, errorCategory: "invalid_identity" }); return; }
        const rec = reconcileIdle(store.getRecording(id));
        if (!rec) { sendJson(res, req.method === "DELETE" ? 200 : 404, { ok: req.method === "DELETE", errorCategory: "not_found" }); return; }
        if (req.method === "GET" && !recordingMatch[2]) { sendJson(res, 200, { ok: true, recording: view(rec) }); return; }
        if (req.method === "POST" && recordingMatch[2]) {
          if (terminal.has(rec.status) || rec.status === "processing" || rec.status === "intake") { respondExisting(res, rec); return; }
          if (!available(rec)) { sendJson(res, 409, { ok: false, id, errorCategory: "audio_unavailable" }); return; }
          store.updateRecording(id, rec.status, rec.error_category, true);
          await processRecording(res, rec); return;
        }
        if (req.method === "DELETE" && !recordingMatch[2]) {
          if (rec.status === "processing" || rec.status === "intake") { sendJson(res, 409, { ok: false, id, errorCategory: "busy" }); return; }
          try {
            const file = ownedPath(rec);
            if (file) files.unlinkSync(file);
            else if (rec.raw_filename && files.existsSync(path.join(rawDir, path.basename(rec.raw_filename)))) {
              sendJson(res, 409, { ok: false, id, errorCategory: "cleanup_incomplete" }); return;
            }
            store.deleteRecording(id);
            sendJson(res, 200, { ok: true, id });
          } catch { sendJson(res, 500, { ok: false, id, errorCategory: "cleanup_incomplete" }); }
          return;
        }
      }

      // -------------------------------------------------------------
      // API: List History
      // -------------------------------------------------------------
      if (req.method === "GET" && pathname === "/api/history") {
        const limit = parseInt(url.searchParams.get("limit") || "50", 10);
        const records = store.listRecordings(limit).map(reconcileIdle).map(view);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, recordings: records }));
        return;
      }

      // -------------------------------------------------------------
      // API: Stream Audio
      // -------------------------------------------------------------
      if ((req.method === "GET" || req.method === "HEAD") && pathname.startsWith("/api/audio/")) {
        const id = pathname.replace("/api/audio/", "").trim();
        const rec = validId(id) ? store.getRecording(id) : null;
        if (!rec) {
          sendJson(res, 404, { ok: false, error: "Recording not found" });
          return;
        }

        const filePath = ownedPath(rec);
        if (!filePath || !available(rec)) {
          sendJson(res, 404, { ok: false, error: "Audio file missing on disk" });
          return;
        }

        const stat = fs.statSync(filePath);
        const ext = path.extname(filePath).toLowerCase();
        const mime = MIME_TYPES[ext] || "audio/webm";

        const range = req.headers.range;
        if (range) {
          const parts = range.replace(/bytes=/, "").split("-");
          const start = parseInt(parts[0], 10);
          const end = parts[1] ? parseInt(parts[1], 10) : stat.size - 1;
          if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start || end >= stat.size) {
            res.writeHead(416, { "Content-Range": `bytes */${stat.size}` }); res.end(); return;
          }
          const chunksize = (end - start) + 1;
          const fileStream = fs.createReadStream(filePath, { start, end });
          res.writeHead(206, {
            "Content-Range": `bytes ${start}-${end}/${stat.size}`,
            "Accept-Ranges": "bytes",
            "Content-Length": chunksize,
            "Content-Type": mime,
          });
          fileStream.pipe(res);
        } else {
          res.writeHead(200, {
            "Content-Length": stat.size,
            "Content-Type": mime,
            "Accept-Ranges": "bytes"
          });
          fs.createReadStream(filePath).pipe(res);
        }
        return;
      }

      // -------------------------------------------------------------
      // Static Files & PWA Assets
      // -------------------------------------------------------------
      let filePath = path.join(publicDir, pathname === "/" ? "index.html" : pathname);

      // If file doesn't exist, check if user requested APK
      if (pathname === "/voice-vault.apk") {
        const apkPath = path.join(publicDir, "voice-vault.apk");
        if (fs.existsSync(apkPath)) {
          const stat = fs.statSync(apkPath);
          res.writeHead(200, {
            "Content-Type": "application/vnd.android.package-archive",
            "Content-Length": stat.size,
            "Content-Disposition": 'attachment; filename="VoiceVault.apk"'
          });
          fs.createReadStream(apkPath).pipe(res);
          return;
        }
      }

      if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        const ext = path.extname(filePath).toLowerCase();
        const mime = MIME_TYPES[ext] || "application/octet-stream";
        res.writeHead(200, { "Content-Type": mime });
        fs.createReadStream(filePath).pipe(res);
        return;
      }

      // 404 Not Found
      res.writeHead(404, { "Content-Type": "text/plain" });
      res.end("Not Found");
    } catch (err) {
      console.error("Server request failed:", category(err));
      sendJson(res, err.statusCode || 500, { ok: false, errorCategory: category(err), error: pathname.startsWith("/api/profile/") && err.statusCode === 400 && /shorter than/.test(err.message) ? "Enrollment clips are shorter than the required minimum" : "Request failed" });
    }
  });
}

export function startServer(options = {}) {
  const server = createAppServer(options);
  const port = options.port ?? PORT;
  const host = options.host ?? HOST;
  return new Promise((resolve) => {
    server.listen(port, host, () => {
      const address = server.address();
      console.log(`Voice Vault server listening on http://${host}:${address.port}`);
      console.log(`Accessible over Tailscale HTTPS at https://qq-box.tail580136.ts.net:3443`);
      resolve({ server, port: address.port, host });
    });
  });
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  startServer();
}
