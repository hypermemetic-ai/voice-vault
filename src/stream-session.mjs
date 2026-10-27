/**
 * Intake of audio streamed while a recording is still being made.
 *
 * A session owns `<id>.stream.pcm` (raw 16 kHz mono PCM16, no header) in the
 * intake folder and an incremental piece cutter. Appends are offset-addressed
 * and idempotent (design D2). Every piece the cutter finalizes is transcribed
 * in the background, one at a time, and cached by sample bounds plus PCM hash
 * so the final recording can reuse it (design D3/D4).
 *
 * The phone keeps the durable WAV copy; this file is only a fast path and is
 * deleted at finish or after an hour without activity (design D6).
 */

import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createPieceCutter } from "./chunker.mjs";
import { defaultTranscribe, pieceCacheKey } from "./transcriber.mjs";
import { encodePcm16Wav } from "./wav.mjs";

/** Append bodies are capped per request; the session itself by the upload limit. */
export const MAX_STREAM_APPEND = 4 * 1024 * 1024;

/** Sessions without activity for this long are swept. */
export const STREAM_IDLE_MS = 60 * 60 * 1000;

const STREAM_SUFFIX = ".stream.pcm";
const SAMPLE_RATE = 16000;

export function streamFileName(id) {
  return `${id}${STREAM_SUFFIX}`;
}

export function isStreamFileName(name) {
  return typeof name === "string" && name.endsWith(STREAM_SUFFIX);
}

/** View little-endian PCM16 bytes as samples without copying when aligned. */
function pcm16View(bytes) {
  if (bytes.length < 2) return new Int16Array(0);
  const source = bytes.byteOffset % 2 === 0 ? bytes : Buffer.from(bytes);
  return new Int16Array(source.buffer, source.byteOffset, source.length >> 1);
}

/**
 * Create one stream session.
 *
 * A file left by an earlier process is adopted at its current size, so a
 * finish after a server restart still verifies it (design D6); it simply has
 * no cached pieces. Appending to an adopted session replays the file into the
 * cutter before the new bytes so later piece boundaries stay identical.
 *
 * @param {object} params
 * @param {string} params.id recording identity
 * @param {string} params.dir intake folder (staging, or the archive)
 * @param {Function} [params.transcribe] injected background backend
 * @param {number} [params.maxBytes] total session size bound
 */
export function createStreamSession({
  id,
  dir,
  transcribe = defaultTranscribe,
  maxBytes = 100 * 1024 * 1024,
  files = fs,
} = {}) {
  if (!id || !dir) throw new Error("stream session needs an id and a directory");
  const file = path.join(dir, streamFileName(id));
  const cutter = createPieceCutter({ sampleRate: SAMPLE_RATE });
  const cache = new Map();
  const chunks = [];
  let totalSamples = 0;
  let received = 0;
  let replayed = false;
  let lastActivity = Date.now();
  let pieceIndex = 0;
  let pending = 0;
  let queue = Promise.resolve();
  let closed = false;

  try {
    const stat = files.lstatSync(file);
    if (stat.isFile() && !stat.isSymbolicLink()) received = stat.size;
  } catch {}

  /** Load an adopted file into the cutter once, discarding old pieces. */
  function replay() {
    if (replayed || received === 0) return;
    replayed = true;
    let bytes;
    try {
      bytes = files.readFileSync(file);
    } catch {
      return;
    }
    const samples = pcm16View(bytes);
    if (samples.length === 0) return;
    chunks.push(samples);
    totalSamples += samples.length;
    // No cache exists after a restart, so these pieces are transcribed again
    // with the final recording; only the cutter state matters here.
    cutter.push(samples);
  }

  /** Copy a sample range out of the buffered chunks (same as the cutter). */
  function readSamples(start, end) {
    const out = new Int16Array(Math.max(0, end - start));
    let base = 0;
    for (const chunk of chunks) {
      const chunkStart = base;
      const chunkEnd = base + chunk.length;
      base = chunkEnd;
      if (chunkEnd <= start) continue;
      if (chunkStart >= end) break;
      const from = Math.max(0, start - chunkStart);
      const to = Math.min(chunk.length, end - chunkStart);
      out.set(chunk.subarray(from, to), chunkStart + from - start);
    }
    return out;
  }

  /** Transcribe one finalized piece; a failure is simply not cached. */
  async function runPiece(piece, index) {
    const pcm = readSamples(piece.startSample, piece.endSample);
    const key = pieceCacheKey(piece.startSample, piece.endSample, pcm);
    if (cache.has(key)) return;
    let tmpDir = null;
    try {
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-stream-"));
      const wavPath = path.join(tmpDir, "piece.wav");
      files.writeFileSync(wavPath, encodePcm16Wav(pcm, SAMPLE_RATE));
      const resp = await transcribe(wavPath, `${id}-s${index}`);
      if (!resp || typeof resp !== "object") return;
      cache.set(key, {
        text: typeof resp.text === "string" ? resp.text : "",
        backend: resp.backend,
        transcribe_ms: resp.transcribe_ms,
        attempts: resp.attempts,
      });
    } catch (error) {
      console.error(`Streamed piece ${id}-s${index} not transcribed: ${error?.message || error}`);
    } finally {
      if (tmpDir) {
        try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch {}
      }
    }
  }

  function enqueue(piece) {
    const index = pieceIndex;
    pieceIndex += 1;
    pending += 1;
    queue = queue
      .then(() => runPiece(piece, index))
      .catch(() => {})
      .finally(() => { pending -= 1; });
  }

  return {
    id,
    file,
    cache,
    /** PCM bytes held so far. */
    get received() { return received; },
    /** Background pieces still queued or running. */
    get pending() { return pending; },
    get lastActivity() { return lastActivity; },

    /**
     * Append `bytes` at PCM byte offset `offset` (design D2).
     * Callers validate that both offset and length are even.
     *
     * @returns {{ok: true, received: number}|{ok: false, errorCategory: string, received: number}}
     */
    append(offset, bytes) {
      if (closed) return { ok: false, errorCategory: "already_saved", received };
      if (offset > received) return { ok: false, errorCategory: "stream_gap", received };
      if (offset + bytes.length <= received) {
        lastActivity = Date.now();
        return { ok: true, received };
      }
      const skip = Math.max(0, received - offset);
      const tail = skip === 0 ? bytes : bytes.subarray(skip);
      if (received + tail.length > maxBytes) {
        return { ok: false, errorCategory: "too_large", received };
      }
      if (tail.length > 0) {
        replay();
        files.appendFileSync(file, tail, { mode: 0o600 });
        received += tail.length;
        const samples = pcm16View(tail);
        if (samples.length > 0) {
          chunks.push(samples);
          totalSamples += samples.length;
          for (const piece of cutter.push(samples)) enqueue(piece);
        }
      }
      lastActivity = Date.now();
      return { ok: true, received };
    },

    /** The raw PCM bytes held in the session file. */
    pcm() {
      try { return files.readFileSync(file); } catch { return Buffer.alloc(0); }
    },

    /** The canonical WAV a client hashes at finish (design D1). */
    wav() {
      return encodePcm16Wav(this.pcm(), SAMPLE_RATE);
    },

    /** Resolves once every queued background piece has settled. */
    whenIdle() {
      return queue;
    },

    /** Forget the session: remove its file. The in-memory cache stays usable. */
    dispose() {
      closed = true;
      try { files.unlinkSync(file); } catch {}
    },
  };
}

/**
 * Delete stream session files idle for more than `maxIdleMs` (design D6).
 * In-memory state for a removed file is dropped from `sessions`.
 *
 * @returns {string[]} removed file paths
 */
export function sweepStreamSessions(dir, sessions = new Map(), { now = Date.now(), maxIdleMs = STREAM_IDLE_MS } = {}) {
  let entries;
  try { entries = fs.readdirSync(dir); } catch { return []; }
  const removed = [];
  for (const name of entries) {
    if (!isStreamFileName(name)) continue;
    const file = path.join(dir, name);
    let stat;
    try { stat = fs.lstatSync(file); } catch { continue; }
    if (!stat.isFile() || stat.isSymbolicLink() || now - stat.mtimeMs < maxIdleMs) continue;
    const id = name.slice(0, -STREAM_SUFFIX.length);
    const session = sessions.get(id);
    sessions.delete(id);
    if (session) session.dispose();
    else { try { fs.unlinkSync(file); } catch {} }
    removed.push(file);
  }
  return removed;
}

/** Hash of a canonical WAV, as the finish endpoint compares it. */
export function wavSha256(wav) {
  return crypto.createHash("sha256").update(wav).digest("hex");
}
