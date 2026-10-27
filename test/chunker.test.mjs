/**
 * Hermetic tests for the incremental long-recording piece cutter.
 *
 * All audio is synthesised in memory (tones, amplitude-modulated bursts and
 * silences); nothing is read from the recordings directory and no model,
 * socket or binary is touched. The tests pin the design D2 rules: bounded
 * pieces, pause-aligned cuts, forced overlap, full coverage, and identical
 * boundaries for batch and arbitrarily chunked input.
 */

import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_PIECE_OPTIONS,
  createPieceCutter,
  cutPieces,
  resolvePieceOptions,
} from "../src/chunker.mjs";

const SAMPLE_RATE = 16000;
const MAX_MS = DEFAULT_PIECE_OPTIONS.maxMs;
const OVERLAP_MS = DEFAULT_PIECE_OPTIONS.overlapMs;

const ENV_KEYS = [
  "VOICE_VAULT_PIECE_MAX_MS",
  "VOICE_VAULT_PIECE_WINDOW_START_MS",
  "VOICE_VAULT_PIECE_WINDOW_END_MS",
  "VOICE_VAULT_PIECE_OVERLAP_MS",
];

async function withEnv(values, fn) {
  const saved = new Map(ENV_KEYS.map((key) => [key, process.env[key]]));
  for (const [key, value] of Object.entries(values)) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = String(value);
  }
  try {
    return await fn();
  } finally {
    for (const [key, value] of saved) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

function seconds(value) {
  return Math.round(value * SAMPLE_RATE);
}

/** Flat continuous tone: literally no pause anywhere. */
function tone(durationSeconds, amplitude = 6000, frequency = 180) {
  const samples = new Int16Array(seconds(durationSeconds));
  for (let i = 0; i < samples.length; i += 1) {
    samples[i] = Math.round(Math.sin((2 * Math.PI * frequency * i) / SAMPLE_RATE) * amplitude);
  }
  return samples;
}

/** Speech-ish amplitude-modulated harmonic burst (same shape as vad tests). */
function burst(durationSeconds, amplitude = 6000, frequency = 180) {
  const samples = new Int16Array(seconds(durationSeconds));
  for (let i = 0; i < samples.length; i += 1) {
    const t = i / SAMPLE_RATE;
    const envelope = 0.55 + 0.45 * Math.sin(2 * Math.PI * 3.1 * t);
    const value =
      Math.sin(2 * Math.PI * frequency * t) * 0.7 +
      Math.sin(2 * Math.PI * frequency * 2 * t) * 0.3;
    samples[i] = Math.round(value * envelope * amplitude);
  }
  return samples;
}

function silence(durationSeconds) {
  return new Int16Array(seconds(durationSeconds));
}

function concat(chunks) {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Int16Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

/** Tile one pattern to reach a duration cheaply (used for the 16-minute case). */
function tile(pattern, durationSeconds) {
  const target = seconds(durationSeconds);
  const out = new Int16Array(target);
  for (let offset = 0; offset < target; offset += pattern.length) {
    out.set(pattern.subarray(0, Math.min(pattern.length, target - offset)), offset);
  }
  return out;
}

function ms(sample) {
  return (sample / SAMPLE_RATE) * 1000;
}

test("audio no longer than one piece is a single piece", () => {
  const pcm = burst(20);
  const pieces = cutPieces(pcm, { sampleRate: SAMPLE_RATE });
  assert.deepEqual(pieces, [{ startSample: 0, endSample: pcm.length, forced: false }]);
});

test("a 400 ms silence at 27 s is the cut point", () => {
  // Continuous speech-like sound everywhere except a 400 ms silence at 27 s.
  const pcm = concat([burst(26.8), silence(0.4), burst(33)]);
  const pieces = cutPieces(pcm, { sampleRate: SAMPLE_RATE });

  assert.ok(pieces.length >= 2, "a 60 s input must be cut");
  const [first] = pieces;
  assert.equal(first.forced, false);
  assert.ok(
    ms(first.endSample) >= 26800 && ms(first.endSample) <= 27400,
    `cut at ${ms(first.endSample)}ms is not inside the 26.8-27.2 s silence`,
  );
  assert.equal(pieces[1].startSample, first.endSample, "pause seams do not overlap");
});

test("continuous sound is a forced cut at the maximum with 1.5 s overlap", () => {
  const pcm = tone(60);
  const pieces = cutPieces(pcm, { sampleRate: SAMPLE_RATE });

  assert.equal(pieces.length, 2);
  assert.deepEqual(pieces[0], {
    startSample: 0,
    endSample: seconds(MAX_MS / 1000),
    forced: true,
  });
  assert.equal(pieces[1].startSample, seconds((MAX_MS - OVERLAP_MS) / 1000));
  assert.equal(pieces[1].endSample, pcm.length);
});

test("a 16-minute input is covered by pieces of at most 45 s, in order", () => {
  const pcm = tile(burst(10), 16 * 60);
  const pieces = cutPieces(pcm, { sampleRate: SAMPLE_RATE });

  assert.ok(pieces.length > 20, `expected many pieces, got ${pieces.length}`);
  assert.equal(pieces[0].startSample, 0);

  let previousEnd = 0;
  for (const [index, piece] of pieces.entries()) {
    assert.ok(piece.endSample > piece.startSample, `piece ${index} is empty`);
    assert.ok(
      piece.endSample - piece.startSample <= seconds(MAX_MS / 1000),
      `piece ${index} is ${ms(piece.endSample - piece.startSample)}ms long`,
    );
    assert.ok(piece.startSample >= previousEnd - seconds(OVERLAP_MS / 1000) - 1, `piece ${index} jumps backwards`);
    assert.ok(piece.startSample <= previousEnd, `piece ${index} leaves a gap`);
    previousEnd = Math.max(previousEnd, piece.endSample);
  }
  assert.equal(pieces[pieces.length - 1].endSample, pcm.length, "the last piece reaches the end");
});

test("one call and 37 irregular pushes produce identical boundaries", () => {
  const pcm = tile(burst(10), 60);

  const batch = cutPieces(pcm, { sampleRate: SAMPLE_RATE });
  assert.ok(batch.length > 1, "the fixture must contain several pieces");

  // Deterministic pseudo-random chunk sizes, including empty pushes.
  let seed = 0x2545f491;
  const nextSize = () => {
    seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
    return (seed % 9000) * 16; // 0 .. 143984 samples
  };

  const cutter = createPieceCutter({ sampleRate: SAMPLE_RATE });
  const incremental = [];
  let offset = 0;
  for (let push = 0; push < 37 && offset < pcm.length; push += 1) {
    const size = Math.min(pcm.length - offset, nextSize());
    incremental.push(...cutter.push(pcm.subarray(offset, offset + size)));
    offset += size;
  }
  if (offset < pcm.length) incremental.push(...cutter.push(pcm.subarray(offset)));
  incremental.push(...cutter.finish());

  assert.deepEqual(incremental, batch);
});

test("an invalid environment combination falls back to the defaults", async () => {
  // A window past the maximum piece length is the design's example.
  await withEnv({ VOICE_VAULT_PIECE_WINDOW_END_MS: 60000 }, () => {
    assert.deepEqual(resolvePieceOptions(), { ...DEFAULT_PIECE_OPTIONS });
  });

  // A non-numeric value is ignored rather than silently turning into NaN.
  await withEnv({ VOICE_VAULT_PIECE_MAX_MS: "not-a-number" }, () => {
    assert.deepEqual(resolvePieceOptions(), { ...DEFAULT_PIECE_OPTIONS });
  });

  // A valid override is honoured.
  await withEnv(
    {
      VOICE_VAULT_PIECE_MAX_MS: 30000,
      VOICE_VAULT_PIECE_WINDOW_START_MS: 12000,
      VOICE_VAULT_PIECE_WINDOW_END_MS: 25000,
      VOICE_VAULT_PIECE_OVERLAP_MS: 1000,
    },
    () => {
      const resolved = resolvePieceOptions();
      assert.equal(resolved.maxMs, 30000);
      assert.equal(resolved.windowStartMs, 12000);
      assert.equal(resolved.windowEndMs, 25000);
      assert.equal(resolved.overlapMs, 1000);
    },
  );
});
