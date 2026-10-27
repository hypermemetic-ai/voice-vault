/**
 * Streamed recording intake over the real routes (design D2, D3, D5-D7).
 *
 * Every scenario from the spec delta runs against an isolated temp folder, a
 * synthetic PCM stream and a stub backend; nothing here reaches a daemon, a
 * model or a real recording.
 */

import test, { after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = fs.mkdtempSync(path.join(os.tmpdir(), "vv-stream-server-"));
process.env.VOICE_VAULT_DB = path.join(root, "history.db");
process.env.VOICE_VAULT_STORAGE = path.join(root, "raw-default");
process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD = "off";
delete process.env.VOICE_VAULT_STAGING_DIR;
delete process.env.VOICE_VAULT_STAGING_SWEEP_MS;
for (const key of [
  "VOICE_VAULT_PIECE_MAX_MS",
  "VOICE_VAULT_PIECE_WINDOW_START_MS",
  "VOICE_VAULT_PIECE_WINDOW_END_MS",
  "VOICE_VAULT_PIECE_OVERLAP_MS",
]) delete process.env[key];

const db = await import("../src/db.mjs");
const { createAppServer } = await import("../src/server.mjs");
const { encodePcm16Wav } = await import("../src/wav.mjs");
const { streamFileName, wavSha256 } = await import("../src/stream-session.mjs");

const SR = 16000;
const servers = [];
let sequence = 0;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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

function canonical(pcm) {
  return { wav: encodePcm16Wav(pcm, SR), pcmBytes: pcm.byteLength };
}

function finishPayload(pcm, extra = {}) {
  const { wav } = canonical(pcm);
  return { bytes: pcm.byteLength, sha256: wavSha256(wav), durationMs: Math.round(pcm.length / 16), device: "test", ...extra };
}

/** Start the real routing with stubbed transcription and isolated folders. */
async function app({ realPipeline = false, ...options } = {}) {
  const rawDir = options.rawDir || path.join(root, `raw-${sequence++}`);
  const calls = [];
  const transcribeAudioFile = realPipeline ? undefined : (options.transcribeAudioFile || (async (file, id) => {
    calls.push({ file, id });
    return {
      text: `text:${id}`, hasSpeech: true, transcribeMs: 1, audioSentMs: 1,
      backend: "stub", backendAttempts: [], gate: { enabled: false },
    };
  }));
  const server = createAppServer({ ...options, rawDir, transcribeAudioFile });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  servers.push(server);
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (route, init) => {
    const response = await fetch(base + route, init);
    return { status: response.status, body: await response.json() };
  };
  return {
    server, rawDir, calls, base, request,
    append: (id, offset, body) => request(`/api/recording/${id}/stream?offset=${offset}`, {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body,
    }),
    finish: (id, payload) => request(`/api/recording/${id}/stream/finish`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    }),
    upload: (id, body, contentType = "audio/wav") => request("/api/transcribe", {
      method: "POST",
      headers: { "Content-Type": contentType, ...(id ? { "X-Recording-Id": id } : {}) },
      body,
    }),
    history: async () => (await request("/api/history?limit=50")).body.recordings,
    sessionFile: (id) => path.join(rawDir, streamFileName(id)),
    close: () => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }),
  };
}

/** Append `pcm` in fixed-size chunks, exactly like the phone streams it. */
async function appendAll(a, id, pcm, stepSeconds = 20) {
  const step = stepSeconds * SR;
  for (let offset = 0; offset < pcm.length; offset += step) {
    const slice = pcm.subarray(offset, Math.min(pcm.length, offset + step));
    const result = await a.append(id, offset * 2, bytesOf(slice));
    assert.equal(result.status, 200, JSON.stringify(result.body));
  }
}

after(async () => {
  for (const server of servers) { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); }
  fs.rmSync(root, { recursive: true, force: true });
});

test("appends are idempotent, report gaps, and reject odd offsets", async () => {
  const a = await app();
  const chunk = bytesOf(flatTone(2));

  const first = await a.append("append-route", 0, chunk);
  assert.deepEqual(first, { status: 200, body: { ok: true, received: chunk.length } });
  const retried = await a.append("append-route", 0, chunk);
  assert.deepEqual(retried, { status: 200, body: { ok: true, received: chunk.length } });
  assert.equal(fs.statSync(a.sessionFile("append-route")).size, chunk.length);

  const gap = await a.append("append-route", chunk.length * 4, chunk);
  assert.equal(gap.status, 409);
  assert.equal(gap.body.errorCategory, "stream_gap");
  assert.equal(gap.body.received, chunk.length);
  assert.equal(fs.statSync(a.sessionFile("append-route")).size, chunk.length);

  const oddOffset = await a.append("append-route", 1, chunk);
  assert.equal(oddOffset.status, 400);
  assert.equal(oddOffset.body.errorCategory, "stream_offset");
  const oddBody = await a.append("append-route", chunk.length, Buffer.alloc(3));
  assert.equal(oddBody.status, 400);
  assert.equal(oddBody.body.errorCategory, "stream_offset");
});

test("a matching finish behaves like an upload, then replays and refuses appends", async () => {
  const a = await app();
  const pcm = flatTone(1);
  await appendAll(a, "finish-ok", pcm);

  const finished = await a.finish("finish-ok", finishPayload(pcm, { durationMs: 1000 }));
  assert.equal(finished.status, 200);
  assert.equal(finished.body.ok, true);
  assert.equal(finished.body.id, "finish-ok");
  assert.equal(finished.body.durationMs, 1000);
  assert.equal(a.calls.length, 1);
  assert.equal(fs.existsSync(a.sessionFile("finish-ok")), false, "the session file is removed");

  const history = await a.history();
  assert.equal(history.filter((recording) => recording.id === "finish-ok").length, 1);

  // The same WAV uploaded later with the same identity is a replay.
  const { wav } = canonical(pcm);
  const replay = await a.upload("finish-ok", wav);
  assert.equal(replay.status, 200);
  assert.deepEqual(replay.body, finished.body);
  assert.equal(a.calls.length, 1, "a replay never transcribes again");

  const late = await a.append("finish-ok", 0, bytesOf(pcm));
  assert.equal(late.status, 409);
  assert.equal(late.body.errorCategory, "already_saved");
});

test("the finish response carries the same fields as an upload", async () => {
  const a = await app();
  const pcm = flatTone(1);
  await appendAll(a, "shape-finish", pcm);
  const finished = await a.finish("shape-finish", finishPayload(pcm));
  const { wav } = canonical(pcm);
  const uploaded = await a.upload("shape-upload", wav);
  assert.equal(finished.status, 200);
  assert.equal(uploaded.status, 200);
  assert.deepEqual(Object.keys(finished.body).sort(), Object.keys(uploaded.body).sort());
});

test("a hash mismatch creates no recording and the full upload still succeeds", async () => {
  const a = await app();
  const pcm = flatTone(1);
  await appendAll(a, "finish-mismatch", pcm);

  const wrong = await a.finish("finish-mismatch", finishPayload(pcm, { sha256: "0".repeat(64) }));
  assert.equal(wrong.status, 409);
  assert.equal(wrong.body.errorCategory, "stream_mismatch");
  assert.equal(wrong.body.received, pcm.byteLength);
  assert.equal(db.getRecording("finish-mismatch"), undefined);
  assert.equal(fs.existsSync(a.sessionFile("finish-mismatch")), false);

  const { wav } = canonical(pcm);
  const fallback = await a.upload("finish-mismatch", wav);
  assert.equal(fallback.status, 200);
  assert.equal(fallback.body.ok, true);
  assert.equal((await a.history()).filter((recording) => recording.id === "finish-mismatch").length, 1);
});

test("finishing without a session answers no_stream", async () => {
  const a = await app();
  const result = await a.finish("never-streamed", finishPayload(flatTone(1)));
  assert.equal(result.status, 404);
  assert.equal(result.body.errorCategory, "no_stream");
});

test("a session idle for more than an hour is swept and no longer finishes", async () => {
  const a = await app({ stagingSweepMs: 25 });
  const pcm = flatTone(1);
  await appendAll(a, "abandoned", pcm);
  const file = a.sessionFile("abandoned");
  const old = new Date(Date.now() - 2 * 60 * 60 * 1000);
  fs.utimesSync(file, old, old);

  const deadline = Date.now() + 3000;
  while (fs.existsSync(file) && Date.now() < deadline) await sleep(25);
  assert.equal(fs.existsSync(file), false, "the idle session file is removed");

  const result = await a.finish("abandoned", finishPayload(pcm));
  assert.equal(result.status, 404);
  assert.equal(result.body.errorCategory, "no_stream");
});

test("a session file left by an earlier process still finishes", async () => {
  const rawDir = path.join(root, "raw-restart");
  const first = await app({ rawDir });
  const pcm = flatTone(1);
  await appendAll(first, "restart-session", pcm);
  await first.close();

  const second = await app({ rawDir });
  const result = await second.finish("restart-session", finishPayload(pcm));
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(second.calls.length, 1);
  assert.equal(db.getRecording("restart-session").status, "transcribed");
});

test("a long stream is transcribed while recording and only its tail after finish", async () => {
  const pieceCalls = [];
  const transcribe = async (wavPath, reqId) => {
    pieceCalls.push(reqId);
    return { text: "synthetic piece", transcribe_ms: 1, backend: "stub" };
  };
  const a = await app({ realPipeline: true, streamTranscribe: transcribe, transcribeOptions: { speakerGate: false, transcribe } });

  const pcm = flatTone(180);
  await appendAll(a, "long-stream", pcm);
  const finished = await a.finish("long-stream", finishPayload(pcm, { durationMs: 180000 }));
  assert.equal(finished.status, 200);
  assert.equal(finished.body.ok, true);

  const streamed = pieceCalls.filter((reqId) => /-s\d+$/.test(reqId));
  const final = pieceCalls.filter((reqId) => /-p\d+$/.test(reqId));
  assert.equal(streamed.length, 4, "the pieces finalized during streaming were transcribed ahead of time");
  assert.equal(final.length, 1, "only the last piece is left at finish");
  assert.equal(finished.body.pieces, 5);
  assert.equal(streamed.length + final.length, finished.body.pieces);
});

test("a fallback upload reuses the pieces already streamed", async () => {
  const pieceCalls = [];
  const transcribe = async (wavPath, reqId) => {
    pieceCalls.push(reqId);
    return { text: "synthetic piece", transcribe_ms: 1, backend: "stub" };
  };
  const a = await app({ realPipeline: true, streamTranscribe: transcribe, transcribeOptions: { speakerGate: false, transcribe } });

  const pcm = flatTone(100);
  await appendAll(a, "fallback-stream", pcm);
  const { wav } = canonical(pcm);
  const uploaded = await a.upload("fallback-stream", wav);
  assert.equal(uploaded.status, 200);
  assert.equal(uploaded.body.ok, true);

  const streamed = pieceCalls.filter((reqId) => /-s\d+$/.test(reqId));
  const final = pieceCalls.filter((reqId) => /-p\d+$/.test(reqId));
  assert.equal(streamed.length, 2);
  assert.equal(final.length, 1, "only the last piece needed the backend");
  assert.equal(uploaded.body.pieces, 3);
  assert.equal(fs.existsSync(a.sessionFile("fallback-stream")), false, "the session is removed with the upload");
  assert.equal((await a.history()).filter((recording) => recording.id === "fallback-stream").length, 1);
});
