/**
 * Regression tests for the low-frequency "No speech" rescue.
 *
 * Real incidents (2026-09-19) showed genuine dictation reported as "No speech":
 * a loud low-frequency component (handling noise, wind, or a partly occluded
 * microphone) dominated the broadband RMS the whole-file VAD measures, raised
 * its noise floor above the speaker and hid real speech. These tests synthesise
 * that failure mode deterministically (real speech buried under low-frequency
 * noise) and pin both the recovery and the no-false-positive behaviour.
 *
 * Hermetic: speech is synthesised with ffmpeg's `flite` filter and the
 * transcriber is a stub, so no GPU, model or binary fixture is required.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { speechFixturesAvailable, tempDbPath } from "./helpers/audio.mjs";

const hasFixtures = speechFixturesAvailable();
const paths = tempDbPath("lf-rescue");
process.env.VOICE_VAULT_DB = paths.db;
process.env.VOICE_VAULT_STORAGE = paths.raw;

const { transcribeAudioFile, VAD_LF_RESCUE_HIGHPASS_HZ } = await import("../src/transcriber.mjs");

const SPEECH = "Please remind me to call the dentist tomorrow morning at nine";
const RUMBLE_AMPLITUDE = 0.05;
const SPEECH_GAIN = 0.05;

function ffmpeg(args) {
  const res = spawnSync("ffmpeg", ["-hide_banner", "-loglevel", "error", ...args], {
    encoding: "utf8",
  });
  if (res.status !== 0) throw new Error(`ffmpeg failed: ${res.stderr || res.stdout}`);
}

function probeDuration(file) {
  const res = spawnSync(
    "ffprobe",
    ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file],
    { encoding: "utf8" },
  );
  return String(res.stdout).trim();
}

let cache = null;
function clips() {
  if (cache) return cache;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "vv-lf-rescue-"));
  const speech = path.join(dir, "speech.wav");
  ffmpeg([
    "-f", "lavfi", "-i", `flite=text='${SPEECH}':voice=slt`,
    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", speech, "-y",
  ]);
  const duration = probeDuration(speech);

  // Low-frequency noise alone: loud enough to dominate the VAD noise floor but
  // carrying no speech. Brown noise through a 150 Hz low-pass mimics the rumble.
  const rumbleOnly = path.join(dir, "rumble-only.wav");
  ffmpeg([
    "-f", "lavfi",
    "-i", `anoisesrc=duration=${duration}:color=brown:amplitude=${RUMBLE_AMPLITUDE}:sample_rate=16000`,
    "-af", "lowpass=f=150",
    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", rumbleOnly, "-y",
  ]);

  // The incident audio: speech attenuated below the rumble, so the broadband
  // VAD sees a loud low-frequency floor with no sustained speech above it.
  const buried = path.join(dir, "speech-plus-rumble.wav");
  ffmpeg([
    "-i", speech,
    "-f", "lavfi",
    "-i", `anoisesrc=duration=${duration}:color=brown:amplitude=${RUMBLE_AMPLITUDE}:sample_rate=16000`,
    "-filter_complex",
    `[0:a]volume=${SPEECH_GAIN}[s];[1:a]lowpass=f=150[r];[s][r]amix=inputs=2:duration=first:normalize=0[out]`,
    "-map", "[out]", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", buried, "-y",
  ]);

  cache = { dir, speech, rumbleOnly, buried };
  return cache;
}

function stubTranscriber(calls) {
  return async (wavPath, reqId) => {
    calls.push({ reqId, bytes: fs.statSync(wavPath).size });
    return { text: "rescued transcript", transcribe_ms: 1, backend: "stub" };
  };
}

test("speech buried under low-frequency rumble is rescued, not reported as no speech", { skip: !hasFixtures }, async () => {
  const files = clips();
  const calls = [];
  const result = await transcribeAudioFile(files.buried, "lf", {
    transcribe: stubTranscriber(calls),
  });

  assert.equal(result.hasSpeech, true, "the hidden speech must be recovered");
  assert.ok(result.text.length > 0, "recovered audio must yield text");
  assert.equal(calls.length, 1, "the recovered audio must reach the transcriber");
  assert.deepEqual(result.rescue, { type: "highpass", hz: VAD_LF_RESCUE_HIGHPASS_HZ });
  assert.notEqual(result.backend, "vad_silence_filter");
});

test("low-frequency rumble with no speech is still reported as no speech", { skip: !hasFixtures }, async () => {
  const files = clips();
  const calls = [];
  const result = await transcribeAudioFile(files.rumbleOnly, "lf-only", {
    transcribe: stubTranscriber(calls),
  });

  assert.equal(result.hasSpeech, false);
  assert.equal(result.backend, "vad_silence_filter");
  assert.equal(result.rescue, null);
  assert.equal(result.text, "");
  assert.equal(calls.length, 0, "no speech must never reach the transcriber");
});

test("plain speech transcribes through the ordinary path with no rescue", { skip: !hasFixtures }, async () => {
  const files = clips();
  const calls = [];
  const result = await transcribeAudioFile(files.speech, "plain", {
    transcribe: stubTranscriber(calls),
  });

  assert.equal(result.hasSpeech, true);
  assert.equal(result.rescue, null, "the ordinary path must be untouched");
  assert.equal(calls.length, 1);
});
