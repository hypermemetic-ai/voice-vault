/**
 * WAV duration parsing and the warm daemon's audio-length bound (design D7).
 *
 * The Bark model allocates a compute buffer that grows with the square of the
 * clip length, so an oversized request can abort the model process. The daemon
 * therefore reads only the RIFF header, derives the `data` duration, and
 * refuses anything past `WHISPER_MAX_AUDIO_MS` (default 120 s) with HTTP 413
 * before `handy` is invoked.
 *
 * This module has no side effects and never spawns or listens on anything, so
 * the parser and decision are unit-testable without starting the daemon.
 */

import fs from "node:fs";

export const DEFAULT_MAX_AUDIO_MS = 120_000;

/** Enough for ordinary WAV headers, including some metadata chunks. */
export const DEFAULT_HEADER_BYTES = 64 * 1024;

/**
 * Read the configured audio bound at call time.
 *
 * @param {Record<string, string|undefined>} [env]
 * @returns {number}
 */
export function resolveMaxAudioMs(env = process.env) {
  const parsed = Number.parseInt(env.WHISPER_MAX_AUDIO_MS ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : DEFAULT_MAX_AUDIO_MS;
}

/**
 * Duration of a RIFF/WAVE buffer in milliseconds, or `null` when the header is
 * unreadable.
 *
 * The `data` payload itself is never required: a 44-byte canonical header is
 * enough, and metadata chunks before `data` are skipped when present.
 *
 * @param {Buffer|Uint8Array} value
 * @returns {number|null}
 */
export function wavDurationMs(value) {
  const wav = Buffer.isBuffer(value) ? value : Buffer.from(value ?? []);
  if (
    wav.length < 12
    || wav.toString("ascii", 0, 4) !== "RIFF"
    || wav.toString("ascii", 8, 12) !== "WAVE"
  ) {
    return null;
  }

  let format = null;
  let dataSize = null;

  for (let offset = 12; offset + 8 <= wav.length;) {
    const id = wav.toString("ascii", offset, offset + 4);
    const size = wav.readUInt32LE(offset + 4);
    const start = offset + 8;

    if (id === "fmt ") {
      if (size < 16 || start + 16 > wav.length) return null;
      format = {
        channels: wav.readUInt16LE(start + 2),
        sampleRate: wav.readUInt32LE(start + 4),
        blockAlign: wav.readUInt16LE(start + 12),
        bitsPerSample: wav.readUInt16LE(start + 14),
      };
    } else if (id === "data") {
      // The payload is left on disk; only its declared size is needed.
      dataSize = size;
      break;
    }

    const end = start + size;
    if (end < start || end + (size % 2) > wav.length) return null;
    offset = end + (size % 2);
  }

  if (!format || dataSize === null || format.sampleRate <= 0) return null;
  const blockAlign = format.blockAlign
    || Math.round((format.channels * format.bitsPerSample) / 8);
  if (!(blockAlign > 0)) return null;

  const frames = Math.floor(dataSize / blockAlign);
  return Math.round((frames / format.sampleRate) * 1000);
}

/**
 * Read the first `maxBytes` of a file for header parsing.
 *
 * Returns `null` on any I/O error, which the daemon treats as an unreadable
 * header and passes through unchanged.
 *
 * @param {string} filePath
 * @param {number} [maxBytes]
 * @returns {Buffer|null}
 */
export function readWavHeader(filePath, maxBytes = DEFAULT_HEADER_BYTES) {
  let fd = null;
  try {
    fd = fs.openSync(filePath, "r");
    const size = Math.min(fs.fstatSync(fd).size, maxBytes);
    const buffer = Buffer.alloc(size);
    fs.readSync(fd, buffer, 0, size, 0);
    return buffer;
  } catch {
    return null;
  } finally {
    if (fd !== null) {
      try { fs.closeSync(fd); } catch {}
    }
  }
}

/**
 * Decide whether a header exceeds the configured bound.
 *
 * @param {Buffer|Uint8Array} header
 * @param {number} [maxMs]
 * @returns {{blocked: boolean, durationMs: number|null, error?: string}}
 */
export function audioBoundDecision(header, maxMs = resolveMaxAudioMs()) {
  const durationMs = wavDurationMs(header);
  if (durationMs === null) return { blocked: false, durationMs: null };
  if (durationMs > maxMs) {
    return {
      blocked: true,
      durationMs,
      error: `audio exceeds ${Math.round(maxMs / 1000)} s bound`,
    };
  }
  return { blocked: false, durationMs };
}
