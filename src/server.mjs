import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { saveRecording, listRecordings, getRecording, deleteRecording } from "./db.mjs";
import { transcribeAudioFile } from "./transcriber.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, "..", "public");
const RAW_DIR = process.env.VOICE_VAULT_STORAGE || "/home/qqp/recordings/voice-vault/raw";
const PORT = parseInt(process.env.PORT || "3005", 10);
const HOST = process.env.HOST || "0.0.0.0";

fs.mkdirSync(RAW_DIR, { recursive: true });

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

const server = http.createServer(async (req, res) => {
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
    // API: Transcribe Audio
    // -------------------------------------------------------------
    if (req.method === "POST" && pathname === "/api/transcribe") {
      const chunks = [];
      let totalBytes = 0;
      const MAX_UPLOAD = 100 * 1024 * 1024; // 100 MB

      for await (const chunk of req) {
        totalBytes += chunk.length;
        if (totalBytes > MAX_UPLOAD) {
          res.writeHead(413, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ ok: false, error: "Upload payload exceeds limit" }));
          return;
        }
        chunks.push(chunk);
      }

      const bodyBuffer = Buffer.concat(chunks);
      if (bodyBuffer.length === 0) {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "Empty audio payload" }));
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
              if (part.filename) {
                const ext = path.extname(part.filename).toLowerCase();
                if (ext) extension = ext;
              } else if (part.contentType.includes("mp4") || part.contentType.includes("m4a")) {
                extension = ".m4a";
              } else if (part.contentType.includes("wav")) {
                extension = ".wav";
              }
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
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "No valid audio data found in request" }));
        return;
      }

      // Generate unique recording ID
      const dateStr = new Date().toISOString().replace(/[:.]/g, "-");
      const id = `${dateStr}_${crypto.randomBytes(4).toString("hex")}`;
      const rawFilename = `${id}${extension}`;
      const rawFilePath = path.join(RAW_DIR, rawFilename);

      // Save raw audio to permanent storage
      fs.writeFileSync(rawFilePath, audioData);

      // Transcribe via Whisper
      const transcribeResult = await transcribeAudioFile(rawFilePath, id);

      // Save to SQLite database
      const dbRecord = saveRecording({
        id,
        createdAt: new Date().toISOString(),
        durationMs,
        clientDevice,
        rawFilename,
        wavFilename: rawFilename, // Can be converted on demand
        transcript: transcribeResult.text,
        status: transcribeResult.hasSpeech ? "transcribed" : "no_speech",
        transcribeMs: transcribeResult.transcribeMs
      });

      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({
        ok: true,
        id,
        text: transcribeResult.text,
        hasSpeech: transcribeResult.hasSpeech,
        durationMs,
        transcribeMs: transcribeResult.transcribeMs,
        audioUrl: `/api/audio/${id}`,
        backend: transcribeResult.backend
      }));
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
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "Recording not found" }));
        return;
      }

      const filePath = path.join(RAW_DIR, rec.raw_filename);
      if (!fs.existsSync(filePath)) {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "Audio file missing on disk" }));
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
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    // -------------------------------------------------------------
    // Static Files & PWA Assets
    // -------------------------------------------------------------
    let filePath = path.join(PUBLIC_DIR, pathname === "/" ? "index.html" : pathname);

    // If file doesn't exist, check if user requested APK
    if (pathname === "/voice-vault.apk") {
      const apkPath = path.join(PUBLIC_DIR, "voice-vault.apk");
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
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: false, error: err.message }));
  }
});

server.listen(PORT, HOST, () => {
  console.log(`Voice Vault server listening on http://${HOST}:${PORT}`);
  console.log(`Accessible over Tailscale HTTPS at https://qq-box.tail580136.ts.net:3443`);
});
