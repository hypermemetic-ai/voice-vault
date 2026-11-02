import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import { spawn } from "node:child_process";
import { resolveDisplay } from "./transcriber.mjs";
import { audioBoundDecision, readWavHeader, resolveMaxAudioMs } from "./audio-bound.mjs";
import { createDaemonQueue, resolveWedgeTimeoutMs } from "./daemon-queue.mjs";

const SOCKET_PATH = process.env.WHISPER_SOCKET || "/tmp/voice-vault-asr.sock";
const HANDY_BIN = process.env.HANDY_BIN || "/home/qqp/.local/bin/handy";
const DEVICE_INDEX = process.env.HANDY_DEVICE_INDEX_GPU ?? "0";
const MODEL = process.env.WHISPER_MODEL || "turbo";

let child = null;
let childRl = null;
let shuttingDown = false;

function log(...args) {
  console.log(`[voice-vault-asr ${new Date().toISOString()}]`, ...args);
}

function logError(...args) {
  console.error(`[voice-vault-asr ${new Date().toISOString()}]`, ...args);
}

const queue = createDaemonQueue({
  write: ({ id, wav }) => child.stdin.write(JSON.stringify({ id, wav }) + "\n"),
  killChild: () => child?.kill("SIGKILL"),
  wedgeTimeoutMs: resolveWedgeTimeoutMs(),
  log,
});

function startHandy() {
  if (shuttingDown) return;

  const display = resolveDisplay();
  log(`Spawning handy: bin=${HANDY_BIN} model=${MODEL} device=${DEVICE_INDEX} display=${display}`);

  const env = {
    ...process.env,
    DISPLAY: display,
    GDK_BACKEND: process.env.GDK_BACKEND || "x11",
    MESA_VK_DEVICE_SELECT: process.env.MESA_VK_DEVICE_SELECT || "1002:1900",
  };

  child = spawn(HANDY_BIN, [
    "--serve-transcription",
    "--model", MODEL,
    "--device-index", String(DEVICE_INDEX),
  ], {
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });

  childRl = readline.createInterface({ input: child.stdout });

  childRl.on("line", (line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    try {
      const msg = JSON.parse(trimmed);
      handleHandyMessage(msg);
    } catch (err) {
      logError("Failed to parse handy stdout JSON:", trimmed, err);
    }
  });

  child.stderr.on("data", (chunk) => {
    const text = chunk.toString("utf8").trim();
    if (text) logError(`[handy] ${text}`);
  });

  child.on("error", (err) => {
    logError("Handy process error:", err);
  });

  child.on("close", (code, signal) => {
    log(`Handy process exited with code ${code}, signal ${signal}`);
    child = null;
    childRl = null;
    queue.childExited(code);

    if (!shuttingDown) {
      log("Restarting Handy in 1000ms...");
      setTimeout(startHandy, 1000);
    }
  });
}

function handleHandyMessage(msg) {
  if (msg.ready) {
    queue.markReady(msg.bound_backend);
    log(`Handy is ready. Backend: ${queue.status().backend}, load_ms: ${msg.load_ms}`);
    return;
  }

  if (!queue.handleReply(msg) && msg.id) {
    log(`Discarding reply for ${msg.id}: no request is waiting for it`);
  }
}

// HTTP Server on Unix domain socket
const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && (req.url === "/health" || req.url === "/status")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ ok: true, ...queue.status() }));
    return;
  }

  if (req.method === "POST" && (req.url === "/" || req.url === "/transcribe")) {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      if (body.length < 1024 * 1024) body += chunk;
    });

    req.on("end", async () => {
      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "Invalid JSON body" }));
        return;
      }

      const { id, wav } = parsed;
      if (!wav || typeof wav !== "string") {
        res.writeHead(400, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: "Missing 'wav' file path" }));
        return;
      }

      if (!fs.existsSync(wav)) {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: `WAV file not found: ${wav}` }));
        return;
      }

      // Refuse an oversized clip before it reaches the model (design D7). An
      // unreadable header is passed through exactly as before.
      const bound = audioBoundDecision(readWavHeader(wav), resolveMaxAudioMs());
      if (bound.blocked) {
        logError(`Refusing ${wav}: ${bound.error}`);
        res.writeHead(413, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: bound.error }));
        return;
      }

      const reqId = id || `req-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;

      // The client gives up after its own bound and falls back to another
      // tier. Drop its request if it is still queued; one already in flight
      // finishes so the warm model is not interrupted.
      const request = queue.enqueue(reqId, wav);
      res.on("close", () => {
        if (!res.writableFinished && request.cancel()) {
          log(`Dropped ${reqId}: client disconnected while it was queued`);
        }
      });

      try {
        const result = await request.promise;
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify(result));
      } catch (err) {
        logError(`Transcription failed for ${reqId}:`, err);
        res.writeHead(500, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: false, error: err.message }));
      }
    });
    return;
  }

  res.writeHead(404, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ ok: false, error: "Not found" }));
});

function cleanup() {
  if (shuttingDown) return;
  shuttingDown = true;
  log("Shutting down whisper daemon...");

  server.close();
  if (fs.existsSync(SOCKET_PATH)) {
    try { fs.unlinkSync(SOCKET_PATH); } catch {}
  }
  if (child) {
    try { child.kill("SIGTERM"); } catch {}
  }
  setTimeout(() => process.exit(0), 500);
}

process.on("SIGINT", cleanup);
process.on("SIGTERM", cleanup);

// Remove stale socket if exists
if (fs.existsSync(SOCKET_PATH)) {
  try {
    fs.unlinkSync(SOCKET_PATH);
  } catch (err) {
    logError(`Could not unlink existing socket ${SOCKET_PATH}:`, err);
  }
}

server.listen(SOCKET_PATH, () => {
  log(`Whisper daemon HTTP server listening on unix socket: ${SOCKET_PATH}`);
  try {
    fs.chmodSync(SOCKET_PATH, 0o666);
  } catch {}
  startHandy();
});
