import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import os from "node:os";
import { fileURLToPath, pathToFileURL } from "node:url";
import { saveRecording, listRecordings, getRecording, deleteRecording } from "./db.mjs";
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
    if (ext) return ext;
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

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
    const pathname = url.pathname;

    // CORS headers for local LAN/Tailscale access
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Content-Type, Content-Length, X-Duration-Ms, X-Device");

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

        // Generate unique recording ID
        const dateStr = new Date().toISOString().replace(/[:.]/g, "-");
        const id = `${dateStr}_${crypto.randomBytes(4).toString("hex")}`;
        const rawFilename = `${id}${extension}`;
        const rawFilePath = path.join(rawDir, rawFilename);

        // Save raw audio to permanent storage
        fs.writeFileSync(rawFilePath, audioData);

        // Transcribe via Whisper (with target-speaker gating when enrolled)
        const transcribeResult = await runTranscription(rawFilePath, id, options.transcribeOptions);

        // Save to SQLite database
        const status = transcribeResult.rejected
          ? "speaker_rejected"
          : transcribeResult.hasSpeech
            ? "transcribed"
            : "no_speech";
        saveRecording({
          id,
          createdAt: new Date().toISOString(),
          durationMs,
          clientDevice,
          rawFilename,
          wavFilename: rawFilename, // Can be converted on demand
          transcript: transcribeResult.text,
          status,
          transcribeMs: transcribeResult.transcribeMs
        });

        sendJson(res, 200, {
          ok: true,
          id,
          text: transcribeResult.text,
          hasSpeech: transcribeResult.hasSpeech,
          rejected: Boolean(transcribeResult.rejected),
          durationMs,
          audioSentMs: transcribeResult.audioSentMs,
          transcribeMs: transcribeResult.transcribeMs,
          audioUrl: `/api/audio/${id}`,
          backend: transcribeResult.backend,
          gate: transcribeResult.gate,
        });
        return;
      }

      // -------------------------------------------------------------
      // API: List History
      // -------------------------------------------------------------
      if (req.method === "GET" && pathname === "/api/history") {
        const limit = parseInt(url.searchParams.get("limit") || "50", 10);
        const records = listRecordings(limit);
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true, recordings: records }));
        return;
      }

      // -------------------------------------------------------------
      // API: Stream Audio
      // -------------------------------------------------------------
      if ((req.method === "GET" || req.method === "HEAD") && pathname.startsWith("/api/audio/")) {
        const id = pathname.replace("/api/audio/", "").trim();
        const rec = getRecording(id);
        if (!rec) {
          sendJson(res, 404, { ok: false, error: "Recording not found" });
          return;
        }

        const filePath = path.join(rawDir, rec.raw_filename);
        if (!fs.existsSync(filePath)) {
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
      // API: Delete Recording
      // -------------------------------------------------------------
      if (req.method === "DELETE" && pathname.startsWith("/api/recording/")) {
        const id = pathname.replace("/api/recording/", "").trim();
        deleteRecording(id);
        sendJson(res, 200, { ok: true });
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
      console.error("Server error:", err);
      sendJson(res, err.statusCode || 500, { ok: false, error: err.message });
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
