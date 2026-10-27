/**
 * Incremental, deterministic cutter for long dictation recordings.
 *
 * The Bark 3.0 Clean model encodes a whole clip as one graph, so its GPU
 * compute buffer grows roughly with the square of the clip length. Recordings
 * longer than one piece are therefore cut into bounded pieces *before* they
 * reach any backend (design D1/D2). A cut sits in the quietest stretch of the
 * 20-40 s window when that stretch is a pause; otherwise it falls at the
 * configured maximum and overlaps the next piece so no word is lost at a hard
 * seam.
 *
 * The cutter accepts audio in arbitrary increments. A cut for the piece
 * starting at `s` is decided only once audio beyond `s + maxMs` has been
 * buffered (or at `finish()`), and the pause threshold is derived only from
 * audio inside `[s, s + windowEndMs]`. Both rules make the result independent
 * of how the audio was split into pushes: batch and incremental cutting emit
 * identical boundaries (spec: Incremental cutting).
 */

import { frameLevelsDb } from "./vad.mjs";

/** Defaults from design D2. Environment overrides are read per call (D9). */
export const DEFAULT_PIECE_OPTIONS = Object.freeze({
  sampleRate: 16000,
  maxMs: 45000,
  windowStartMs: 20000,
  windowEndMs: 40000,
  overlapMs: 1500,
  minPauseMs: 200,
});

const ENV_NAMES = Object.freeze({
  maxMs: "VOICE_VAULT_PIECE_MAX_MS",
  windowStartMs: "VOICE_VAULT_PIECE_WINDOW_START_MS",
  windowEndMs: "VOICE_VAULT_PIECE_WINDOW_END_MS",
  overlapMs: "VOICE_VAULT_PIECE_OVERLAP_MS",
});

// Pause detection mirrors segmentSpeech (src/vad.mjs): the noise floor is the
// 20th percentile of frame levels, speech sits `noiseMarginDb` above it, and
// the absolute floor keeps digital silence a pause.
const ABSOLUTE_THRESHOLD_DB = -50;
const NOISE_MARGIN_DB = 9;
const NOISE_PERCENTILE = 0.2;
const MIN_ACTIVITY_RANGE_DB = 6;

function finiteNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function envNumber(name) {
  const raw = process.env[name];
  if (typeof raw !== "string" || !raw.trim()) return null;
  return finiteNumber(raw.trim());
}

function validOptions(options) {
  return options.sampleRate > 0
    && options.maxMs > 0
    && options.windowStartMs >= 0
    && options.windowStartMs < options.windowEndMs
    && options.windowEndMs <= options.maxMs
    && options.overlapMs >= 0
    && options.overlapMs < options.maxMs
    && options.minPauseMs > 0
    && options.minPauseMs <= options.windowEndMs - options.windowStartMs;
}

/**
 * Resolve cutter options from explicit values, environment and defaults.
 *
 * Explicit options win over the `VOICE_VAULT_PIECE_*` environment variables,
 * which win over {@link DEFAULT_PIECE_OPTIONS}. An invalid combination (for
 * example a cut window past the maximum piece length) falls back to the
 * defaults, as required by design D9.
 *
 * @param {object} [options]
 * @returns {typeof DEFAULT_PIECE_OPTIONS}
 */
export function resolvePieceOptions(options = {}) {
  const explicitRate = finiteNumber(options.sampleRate);
  const sampleRate = explicitRate && explicitRate > 0
    ? explicitRate
    : DEFAULT_PIECE_OPTIONS.sampleRate;
  const resolved = { sampleRate, minPauseMs: DEFAULT_PIECE_OPTIONS.minPauseMs };
  for (const [key, envName] of Object.entries(ENV_NAMES)) {
    resolved[key] = finiteNumber(options[key]) ?? envNumber(envName) ?? DEFAULT_PIECE_OPTIONS[key];
  }
  resolved.minPauseMs = finiteNumber(options.minPauseMs) ?? DEFAULT_PIECE_OPTIONS.minPauseMs;

  if (!validOptions(resolved)) {
    return { ...DEFAULT_PIECE_OPTIONS, sampleRate };
  }
  return resolved;
}

function percentile(values, proportion) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.floor((sorted.length - 1) * proportion)));
  return sorted[index];
}

/** Copy `[start, end)` out of a chunk list without copying the whole buffer. */
function readRange(chunks, start, end) {
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

/**
 * Find the cut sample for a piece starting at `spanStart`, or `null` when the
 * cut window holds no pause.
 *
 * @param {Int16Array} span audio from the piece start through the window end
 * @param {number} spanStart absolute sample offset of `span`
 * @param {object} options resolved piece options
 * @returns {number|null}
 */
function findPauseCut(span, spanStart, options) {
  const { sampleRate } = options;
  const { levels, frameSamples, hopSamples } = frameLevelsDb(span, sampleRate);
  if (levels.length === 0) return null;

  const noiseFloorDb = percentile(levels, NOISE_PERCENTILE);
  const upperDb = percentile(levels, 1 - NOISE_PERCENTILE);
  const hasQuietRegion = noiseFloorDb <= ABSOLUTE_THRESHOLD_DB;
  // Frame levels are already dBFS, so the dynamic range is a plain difference.
  const hasSpeechContrast = upperDb - noiseFloorDb >= MIN_ACTIVITY_RANGE_DB;
  const loudEnough = upperDb >= ABSOLUTE_THRESHOLD_DB;
  // This is segmentSpeech's continuous-audio guard: flat, loud audio carries
  // no pause. Without it the noise-margin threshold would classify the
  // quietest stretch of any signal as a pause and forced cuts could never run.
  if (!hasQuietRegion && !hasSpeechContrast && loudEnough) return null;

  const thresholdDb = Math.max(ABSOLUTE_THRESHOLD_DB, noiseFloorDb + NOISE_MARGIN_DB);
  const windowStartSamples = Math.round((sampleRate * options.windowStartMs) / 1000);
  const windowEndSamples = Math.round((sampleRate * options.windowEndMs) / 1000);
  const pauseSamples = Math.round((sampleRate * options.minPauseMs) / 1000);
  const pauseFrames = Math.max(1, Math.ceil(pauseSamples / hopSamples));

  // Candidate 10 ms-hop frames lying fully inside the cut window.
  const candidates = [];
  for (let index = 0; index < levels.length; index += 1) {
    const start = index * hopSamples;
    if (start >= windowStartSamples && start + frameSamples <= windowEndSamples) {
      candidates.push(index);
    }
  }
  if (candidates.length < pauseFrames) return null;

  // Slide the pause window and keep the lowest mean level (earliest on ties).
  let sum = 0;
  for (let i = 0; i < pauseFrames; i += 1) sum += levels[candidates[i]];
  let bestOffset = 0;
  let bestMean = sum / pauseFrames;
  for (let offset = 1; offset + pauseFrames <= candidates.length; offset += 1) {
    sum += levels[candidates[offset + pauseFrames - 1]] - levels[candidates[offset - 1]];
    const mean = sum / pauseFrames;
    if (mean < bestMean) {
      bestMean = mean;
      bestOffset = offset;
    }
  }

  if (!(bestMean < thresholdDb)) return null;

  const firstFrame = candidates[bestOffset];
  const lastFrame = candidates[bestOffset + pauseFrames - 1];
  const stretchStart = firstFrame * hopSamples;
  const stretchEnd = lastFrame * hopSamples + frameSamples;
  return spanStart + Math.round((stretchStart + stretchEnd) / 2);
}

/**
 * Create an incremental piece cutter.
 *
 * `push(int16)` buffers audio and returns the pieces whose cut is now final;
 * `finish()` returns the remaining pieces. Pieces are
 * `{ startSample, endSample, forced }`; a forced cut sets the next piece's
 * `startSample` `overlapMs` before this piece's `endSample`.
 *
 * @param {object} [options] see {@link resolvePieceOptions}
 */
export function createPieceCutter(options = {}) {
  const resolved = resolvePieceOptions(options);
  const { sampleRate } = resolved;
  const maxSamples = Math.max(1, Math.round((sampleRate * resolved.maxMs) / 1000));
  const overlapSamples = Math.max(0, Math.round((sampleRate * resolved.overlapMs) / 1000));

  const chunks = [];
  let totalSamples = 0;
  let nextStart = 0;
  let finished = false;

  function decideCut() {
    const start = nextStart;
    const remaining = totalSamples - start;

    // The remainder is known at finish() (or already exceeded the maximum):
    // a remainder within one piece is the last piece.
    if (remaining <= maxSamples) {
      nextStart = totalSamples;
      return { startSample: start, endSample: totalSamples, forced: false };
    }

    const spanEnd = Math.min(totalSamples, start + Math.round((sampleRate * resolved.windowEndMs) / 1000));
    const span = readRange(chunks, start, spanEnd);
    const cutSample = findPauseCut(span, start, resolved);

    if (cutSample === null) {
      const endSample = start + maxSamples;
      nextStart = Math.max(start + 1, endSample - overlapSamples);
      return { startSample: start, endSample, forced: true };
    }

    nextStart = cutSample;
    return { startSample: start, endSample: cutSample, forced: false };
  }

  function drain(final) {
    const pieces = [];
    while (nextStart < totalSamples) {
      // Without finish(), wait until audio beyond the maximum exists so the
      // last-piece decision can never be made early (incremental determinism).
      if (!final && nextStart + maxSamples >= totalSamples) break;
      pieces.push(decideCut());
    }
    return pieces;
  }

  return {
    /** Append PCM16 audio; returns any pieces finalized by this push. */
    push(input) {
      if (finished) throw new Error("piece cutter is already finished");
      const samples = input instanceof Int16Array ? input : Int16Array.from(input ?? []);
      if (samples.length > 0) {
        chunks.push(samples);
        totalSamples += samples.length;
      }
      return drain(false);
    },
    /** Finish the stream; returns the remaining pieces. */
    finish() {
      if (finished) return [];
      finished = true;
      return drain(true);
    },
  };
}

/** Cut a complete PCM16 buffer at once. Equivalent to push(all) + finish(). */
export function cutPieces(pcm, options = {}) {
  const cutter = createPieceCutter(options);
  return [...cutter.push(pcm), ...cutter.finish()];
}
