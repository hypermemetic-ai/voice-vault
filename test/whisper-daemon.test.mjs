/**
 * Hermetic tests for the warm daemon's audio-length guard (design D7).
 *
 * The daemon module starts a server and spawns `handy` at import time, so it is
 * never imported here. Instead these tests exercise the side-effect-free
 * `src/audio-bound.mjs` helpers the daemon uses: the RIFF header duration
 * parser and the 413 decision. No daemon, socket, GPU or model is touched.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  DEFAULT_MAX_AUDIO_MS,
  audioBoundDecision,
  readWavHeader,
  resolveMaxAudioMs,
  wavDurationMs,
} from "../src/audio-bound.mjs";

/**
 * Build a WAV header (no payload) declaring `durationSeconds` of audio.
 * `extraChunks` inserts RIFF chunks between `fmt ` and `data`.
 */
function wavHeader(durationSeconds, {
  sampleRate = 16000,
  channels = 1,
  bitsPerSample = 16,
  extraChunks = [],
} = {}) {
  const blockAlign = channels * (bitsPerSample / 8);
  const dataSize = Math.round(durationSeconds * sampleRate) * blockAlign;

  const fmt = Buffer.alloc(24);
  fmt.write("fmt ", 0);
  fmt.writeUInt32LE(16, 4);
  fmt.writeUInt16LE(1, 8);
  fmt.writeUInt16LE(channels, 10);
  fmt.writeUInt32LE(sampleRate, 12);
  fmt.writeUInt32LE(sampleRate * blockAlign, 16);
  fmt.writeUInt16LE(blockAlign, 20);
  fmt.writeUInt16LE(bitsPerSample, 22);

  const extras = extraChunks.map(({ id, payload }) => {
    const chunk = Buffer.alloc(8 + payload.length + (payload.length % 2));
    chunk.write(id, 0);
    chunk.writeUInt32LE(payload.length, 4);
    payload.copy(chunk, 8);
    return chunk;
  });

  const data = Buffer.alloc(8);
  data.write("data", 0);
  data.writeUInt32LE(dataSize, 4);

  const body = Buffer.concat([fmt, ...extras, data]);
  const header = Buffer.alloc(12 + body.length);
  header.write("RIFF", 0);
  header.writeUInt32LE(4 + body.length + dataSize, 4);
  header.write("WAVE", 8);
  body.copy(header, 12);
  return header;
}

test("a 10-minute WAV header exceeds the default 120 s bound", () => {
  const header = wavHeader(600);
  assert.equal(wavDurationMs(header), 600_000);

  const decision = audioBoundDecision(header, DEFAULT_MAX_AUDIO_MS);
  assert.equal(decision.blocked, true);
  assert.equal(decision.durationMs, 600_000);
  assert.equal(decision.error, "audio exceeds 120 s bound");
});

test("a 30 s WAV header is allowed", () => {
  const header = wavHeader(30);
  assert.equal(wavDurationMs(header), 30_000);

  const decision = audioBoundDecision(header, DEFAULT_MAX_AUDIO_MS);
  assert.equal(decision.blocked, false);
  assert.equal(decision.durationMs, 30_000);
  assert.equal(decision.error, undefined);
});

test("the error names a configured bound", () => {
  const decision = audioBoundDecision(wavHeader(30), 20_000);
  assert.equal(decision.blocked, true);
  assert.equal(decision.error, "audio exceeds 20 s bound");
});

test("metadata chunks before data are skipped and block alignment is honoured", () => {
  const list = Buffer.from("INFOISFT\x00\x00", "latin1");
  list.writeUInt32LE(8, 4);
  const header = wavHeader(45, {
    channels: 2,
    bitsPerSample: 16,
    extraChunks: [{ id: "LIST", payload: list.subarray(0, 12) }],
  });
  assert.equal(wavDurationMs(header), 45_000);
});

test("an unreadable header is passed through rather than refused", () => {
  for (const header of [
    Buffer.from("definitely not a wav file"),
    Buffer.alloc(0),
    Buffer.from("RIFF\x00\x00\x00\x00WAVE", "latin1"),
  ]) {
    assert.equal(wavDurationMs(header), null);
    const decision = audioBoundDecision(header, DEFAULT_MAX_AUDIO_MS);
    assert.equal(decision.blocked, false);
    assert.equal(decision.durationMs, null);
  }
});

test("WHISPER_MAX_AUDIO_MS is read per call and falls back to 120 s", () => {
  assert.equal(resolveMaxAudioMs({}), DEFAULT_MAX_AUDIO_MS);
  assert.equal(DEFAULT_MAX_AUDIO_MS, 120_000);
  assert.equal(resolveMaxAudioMs({ WHISPER_MAX_AUDIO_MS: "60000" }), 60_000);
  assert.equal(resolveMaxAudioMs({ WHISPER_MAX_AUDIO_MS: "not-a-number" }), DEFAULT_MAX_AUDIO_MS);
  assert.equal(resolveMaxAudioMs({ WHISPER_MAX_AUDIO_MS: "-5" }), DEFAULT_MAX_AUDIO_MS);
});

test("the daemon reads the header from disk before deciding", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vv-audio-bound-"));
  const file = path.join(dir, "ten-minutes.wav");
  // Header only: the guard must not need the 19 MB payload.
  fs.writeFileSync(file, wavHeader(600));
  try {
    const header = readWavHeader(file);
    assert.ok(header, "the header file must be readable");
    const decision = audioBoundDecision(header, DEFAULT_MAX_AUDIO_MS);
    assert.equal(decision.blocked, true);
    assert.equal(decision.error, "audio exceeds 120 s bound");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("a missing file has no header and passes through", () => {
  assert.equal(readWavHeader("/nonexistent/voice-vault.wav"), null);
  const decision = audioBoundDecision(null, DEFAULT_MAX_AUDIO_MS);
  assert.equal(decision.blocked, false);
});
