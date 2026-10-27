/**
 * Streamed intake sessions: offset appends (design D2), ahead-of-time piece
 * transcription (D3), and abandonment (D6). Everything here is synthetic PCM in
 * a temp directory; no backend is ever reached.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "vv-stream-session-"));
process.env.VOICE_VAULT_DB = path.join(root, "history.db");
process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD = "off";
for (const key of [
  "VOICE_VAULT_PIECE_MAX_MS",
  "VOICE_VAULT_PIECE_WINDOW_START_MS",
  "VOICE_VAULT_PIECE_WINDOW_END_MS",
  "VOICE_VAULT_PIECE_OVERLAP_MS",
]) delete process.env[key];

const {
  STREAM_IDLE_MS,
  createStreamSession,
  isStreamFileName,
  streamFileName,
  sweepStreamSessions,
  wavSha256,
} = await import("../src/stream-session.mjs");
const { cutPieces, resolvePieceOptions } = await import("../src/chunker.mjs");
const { pieceCacheKey } = await import("../src/transcriber.mjs");
const { encodePcm16Wav } = await import("../src/wav.mjs");

const SR = 16000;

function flatTone(durationSeconds, amplitude = 6000, frequency = 180) {
  const samples = new Int16Array(Math.round(durationSeconds * SR));
  for (let i = 0; i < samples.length; i += 1) {
    samples[i] = Math.round(Math.sin((2 * Math.PI * frequency * i) / SR) * amplitude);
  }
  return samples;
}

function bytesOf(samples) {
  return Buffer.from(samples.buffer, samples.byteOffset, samples.byteLength);
}

function sessionDir(label) {
  return fs.mkdtempSync(path.join(root, `dir-${label}-`));
}

test("repeated and overlapping appends are idempotent and gaps report the received count", () => {
  const dir = sessionDir("append");
  const session = createStreamSession({ id: "appends", dir, transcribe: async () => ({ text: "" }) });
  const chunk = bytesOf(flatTone(2));

  assert.deepEqual(session.append(0, chunk), { ok: true, received: chunk.length });
  assert.deepEqual(session.append(0, chunk), { ok: true, received: chunk.length });
  assert.equal(fs.statSync(session.file).size, chunk.length, "the retried append stored nothing new");

  const overlap = session.append(chunk.length / 2, chunk);
  assert.deepEqual(overlap, { ok: true, received: chunk.length + chunk.length / 2 });
  assert.deepEqual(
    fs.readFileSync(session.file),
    Buffer.concat([chunk, chunk.subarray(chunk.length / 2)]),
  );

  const gap = session.append(chunk.length * 4, chunk);
  assert.deepEqual(gap, { ok: false, errorCategory: "stream_gap", received: chunk.length * 3 / 2 });
  assert.equal(fs.statSync(session.file).size, chunk.length * 3 / 2, "a gap stores nothing");

  const bounded = createStreamSession({ id: "bounded", dir, transcribe: async () => ({ text: "" }), maxBytes: 100 });
  assert.deepEqual(bounded.append(0, Buffer.alloc(200)), { ok: false, errorCategory: "too_large", received: 0 });
  assert.equal(fs.existsSync(bounded.file), false);
});

test("finalized pieces are transcribed in the background, one at a time, and cached", async () => {
  const dir = sessionDir("pieces");
  const full = flatTone(180);
  const calls = [];
  let active = 0;
  let maxActive = 0;
  const session = createStreamSession({
    id: "long",
    dir,
    transcribe: async (wavPath, reqId) => {
      active += 1;
      maxActive = Math.max(maxActive, active);
      calls.push(reqId);
      await new Promise((resolve) => setTimeout(resolve, 1));
      active -= 1;
      return { text: `heard ${reqId}`, transcribe_ms: 2, backend: "stub", attempts: [] };
    },
  });

  const step = 20 * SR;
  for (let offset = 0; offset < full.length; offset += step) {
    const chunk = full.subarray(offset, Math.min(full.length, offset + step));
    assert.equal(session.append(offset * 2, bytesOf(chunk)).ok, true);
  }
  await session.whenIdle();

  // 3 minutes of continuous tone cut at 45 s: four pieces finalize while
  // streaming and the last one only at finish (design D3).
  assert.deepEqual(calls, ["long-s0", "long-s1", "long-s2", "long-s3"]);
  assert.equal(maxActive, 1, "pieces are transcribed strictly one at a time");
  assert.equal(session.cache.size, 4);

  const options = resolvePieceOptions({ sampleRate: SR });
  const pieces = cutPieces(full, options);
  for (let index = 0; index < 4; index += 1) {
    const piece = pieces[index];
    const key = pieceCacheKey(piece.startSample, piece.endSample, full.subarray(piece.startSample, piece.endSample));
    assert.deepEqual(session.cache.get(key), {
      text: `heard long-s${index}`,
      backend: "stub",
      transcribe_ms: 2,
      attempts: [],
    });
  }
});

/** Irregular bursts and pauses: no two stretches repeat, unlike a flat tone. */
function irregularSpeech(durationSeconds) {
  const samples = new Int16Array(durationSeconds * SR);
  let seed = 7;
  const random = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  let index = 0;
  while (index < samples.length) {
    const burst = Math.round((1 + random() * 4) * SR);
    for (let i = 0; i < burst && index < samples.length; i += 1, index += 1) {
      samples[index] = Math.round((random() * 2 - 1) * 5000 * (0.6 + 0.4 * Math.sin(index / 900)));
    }
    index += Math.round((0.25 + random() * 0.6) * SR);
  }
  return samples;
}

test("pieces cut from small streamed appends match the final recording's pieces", async () => {
  const dir = sessionDir("small-appends");
  const full = irregularSpeech(130);
  const session = createStreamSession({
    id: "appends",
    dir,
    transcribe: async (wavPath, reqId) => ({ text: reqId, backend: "stub" }),
  });
  const step = 3 * SR;
  for (let offset = 0; offset < full.length; offset += step) {
    session.append(offset * 2, bytesOf(full.subarray(offset, Math.min(full.length, offset + step))));
  }
  await session.whenIdle();

  const pieces = cutPieces(full, resolvePieceOptions({ sampleRate: SR }));
  const expected = pieces.slice(0, session.cache.size)
    .map((piece) => pieceCacheKey(piece.startSample, piece.endSample, full.subarray(piece.startSample, piece.endSample)));
  assert.ok(session.cache.size >= 2, `only ${session.cache.size} pieces finalized`);
  assert.deepEqual([...session.cache.keys()], expected);
});

test("a failed piece is not cached and later pieces still run", async () => {
  const dir = sessionDir("failure");
  const full = flatTone(100);
  const calls = [];
  const session = createStreamSession({
    id: "failing",
    dir,
    transcribe: async (wavPath, reqId) => {
      calls.push(reqId);
      if (reqId === "failing-s0") throw new Error("backend down");
      return { text: "later", transcribe_ms: 1, backend: "stub" };
    },
  });
  const step = 20 * SR;
  for (let offset = 0; offset < full.length; offset += step) {
    session.append(offset * 2, bytesOf(full.subarray(offset, Math.min(full.length, offset + step))));
  }
  await session.whenIdle();

  assert.deepEqual(calls, ["failing-s0", "failing-s1"]);
  assert.equal(session.cache.size, 1, "only the successful piece is cached");
});

test("a file left by an earlier process is adopted at its size and still verifies", () => {
  const dir = sessionDir("adopt");
  const first = createStreamSession({ id: "adopted", dir, transcribe: async () => ({ text: "" }) });
  const pcm = flatTone(50);
  first.append(0, bytesOf(pcm));
  const expectedWav = encodePcm16Wav(pcm, SR);

  // A new process: same file, no in-memory cutter or cache.
  const adopted = createStreamSession({ id: "adopted", dir, transcribe: async () => ({ text: "" }) });
  assert.equal(adopted.received, pcm.byteLength);
  assert.equal(adopted.cache.size, 0);
  assert.deepEqual(adopted.pcm(), bytesOf(pcm));
  assert.deepEqual(adopted.wav(), expectedWav);
  assert.equal(wavSha256(adopted.wav()), wavSha256(expectedWav));
  assert.equal(first.file, adopted.file);
});

test("sweeping removes only sessions idle for more than an hour", () => {
  const dir = sessionDir("sweep");
  const sessions = new Map();
  const stale = createStreamSession({ id: "stale", dir, transcribe: async () => ({ text: "" }) });
  const fresh = createStreamSession({ id: "fresh", dir, transcribe: async () => ({ text: "" }) });
  sessions.set(stale.id, stale);
  sessions.set(fresh.id, fresh);
  stale.append(0, bytesOf(flatTone(1)));
  fresh.append(0, bytesOf(flatTone(1)));

  const old = new Date(Date.now() - STREAM_IDLE_MS - 60_000);
  fs.utimesSync(stale.file, old, old);

  const removed = sweepStreamSessions(dir, sessions);
  assert.deepEqual(removed, [stale.file]);
  assert.equal(fs.existsSync(stale.file), false);
  assert.equal(fs.existsSync(fresh.file), true);
  assert.equal(sessions.has("stale"), false);
  assert.equal(sessions.has("fresh"), true);
  assert.equal(isStreamFileName(streamFileName("fresh")), true);
});

test("dispose removes the session file and stops further appends", () => {
  const dir = sessionDir("dispose");
  const session = createStreamSession({ id: "disposed", dir, transcribe: async () => ({ text: "" }) });
  session.append(0, bytesOf(flatTone(1)));
  assert.equal(fs.existsSync(session.file), true);
  session.dispose();
  assert.equal(fs.existsSync(session.file), false);
  assert.equal(session.append(0, bytesOf(flatTone(1))).errorCategory, "already_saved");
});
