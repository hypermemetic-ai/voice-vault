import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import { spawn } from "node:child_process";
import { resolveDisplay } from "./transcriber.mjs";

const SOCKET_PATH = process.env.WHISPER_SOCKET || "/tmp/orca_whisper.sock";
const HANDY_BIN = process.env.HANDY_BIN || "/home/qqp/.local/bin/handy";
const DEVICE_INDEX = process.env.HANDY_DEVICE_INDEX_GPU ?? "0";
const MODEL = process.env.WHISPER_MODEL || "turbo";

let child = null;
let childRl = null;
let isReady = false;
let boundBackend = "unknown";
let shuttingDown = false;

// Queue of pending requests: { id, wav, resolve, reject, timer }
const requestQueue = [];
let inFlight = null;

function log(...args) {
  console.log(`[whisper-daemon ${new Date().toISOString()}]`, ...args);
}

function logError(...args) {
  console.error(`[whisper-daemon ${new Date().toISOString()}]`, ...args);
}

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
    isReady = false;
    child = null;
    childRl = null;

    // Fail any in-flight or queued requests
    if (inFlight) {
      clearTimeout(inFlight.timer);
      inFlight.reject(new Error(`Handy process exited unexpectedly (code ${code})`));
      inFlight = null;
    }
    while (requestQueue.length > 0) {
      const req = requestQueue.shift();
      clearTimeout(req.timer);
      req.reject(new Error("Handy process restarted"));
    }

    if (!shuttingDown) {
      log("Restarting Handy in 1000ms...");
      setTimeout(startHandy, 1000);
    }
  });
}

function handleHandyMessage(msg) {
  if (msg.ready) {
    isReady = true;
    boundBackend = msg.bound_backend || "ready";
    log(`Handy is ready. Backend: ${boundBackend}, load_ms: ${msg.load_ms}`);
    pumpQueue();
    return;
  }

  if (inFlight && msg.id === inFlight.id) {
    clearTimeout(inFlight.timer);
    const curr = inFlight;
    inFlight = null;

    if (msg.ok === false) {
      curr.reject(new Error(msg.error || "Handy transcription failed"));
    } else {
      curr.resolve({
        ok: true,
        text: msg.text || "",
        transcribe_ms: msg.transcribe_ms,
        backend: msg.bound_backend || boundBackend,
      });
    }

    pumpQueue();
  } else if (msg.id) {
    logError(`Received response for unexpected id: ${msg.id}`);
  }
}

function pumpQueue() {
  if (!isReady || !child || inFlight || requestQueue.length === 0) {
    return;
  }

  inFlight = requestQueue.shift();
  try {
    const payload = JSON.stringify({ id: inFlight.id, wav: inFlight.wav }) + "\n";
    child.stdin.write(payload);
  } catch (err) {
    logError("Failed to write to handy stdin:", err);
    clearTimeout(inFlight.timer);
    const curr = inFlight;
    inFlight = null;
    curr.reject(err);
    pumpQueue();
  }
}

function enqueueTranscription(id, wavPath) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      // Find in queue or check if inFlight
      const qIdx = requestQueue.findIndex((item) => item.id === id);
      if (qIdx !== -1) {
        requestQueue.splice(qIdx, 1);
        reject(new Error(`Transcription queued timeout after 15000ms`));
      } else if (inFlight && inFlight.id === id) {
        inFlight = null;
        reject(new Error(`Transcription processing timeout after 15000ms`));
        // Reset handy since it might be wedged
        if (child) {
          log("Killing wedged handy process...");
          child.kill("SIGKILL");
        }
      }
    }, 15000);

    requestQueue.push({ id, wav: wavPath, resolve, reject, timer });
    pumpQueue();
  });
}

// HTTP Server on Unix domain socket
const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && (req.url === "/health" || req.url === "/status")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      ok: true,
      ready: isReady,
      backend: boundBackend,
      queueLength: requestQueue.length,
      inFlight: !!inFlight,
    }));
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

      const reqId = id || `req-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;

      try {
        const result = await enqueueTranscription(reqId, wav);
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
