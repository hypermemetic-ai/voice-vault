/**
 * Deterministic speech fixtures for the speaker-gating tests.
 *
 * ffmpeg's `flite` filter synthesises real speech from three distinct voices,
 * which is enough to exercise enrollment, calibration and rejection without
 * shipping binary test audio in the repository. Fixtures are generated once per
 * test process into an isolated temp directory.
 */

import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { decodeWav } from "../../src/wav.mjs";

export const VOICE_A = "slt";
export const VOICE_B = "kal";
export const VOICE_C = "awb";

const TEXTS = {
  a1: "The quick brown fox jumps over the lazy dog near the river bank",
  a2: "Please remind me to call the dentist tomorrow morning at nine",
  a3: "Voice vault keeps every dictation private on the local machine",
  b1: "I will bring the documents to the meeting after lunch today",
  b2: "The weather forecast says it will rain again this weekend",
  b3: "Remember to water the plants before leaving for the airport",
  yes: "Yes. Okay. Sure.",
};

export function ffmpegAvailable() {
  const result = spawnSync("ffmpeg", ["-hide_banner", "-version"], { stdio: "ignore" });
  return result.status === 0;
}

function fliteAvailable() {
  const result = spawnSync("ffmpeg", ["-hide_banner", "-filters"], { encoding: "utf8" });
  return result.status === 0 && /\bflite\b/.test(result.stdout || "");
}

export function speechFixturesAvailable() {
  return ffmpegAvailable() && fliteAvailable();
}

let fixtureDir = null;

export function fixtures() {
  if (fixtureDir) return fixtureDir;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "voice-vault-fixtures-"));
  const run = (args) => {
    const result = spawnSync("ffmpeg", ["-hide_banner", "-loglevel", "error", ...args], {
      encoding: "utf8",
    });
    if (result.status !== 0) {
      throw new Error(`ffmpeg failed: ${result.stderr || result.stdout}`);
    }
  };

  const synthesize = (voice, text, name) => {
    const file = path.join(dir, `${name}.wav`);
    run([
      "-f", "lavfi",
      "-i", `flite=text='${text}':voice=${voice}`,
      "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
      file, "-y",
    ]);
    return file;
  };

  const files = {
    a1: synthesize(VOICE_A, TEXTS.a1, "speaker_a_1"),
    a2: synthesize(VOICE_A, TEXTS.a2, "speaker_a_2"),
    a3: synthesize(VOICE_A, TEXTS.a3, "speaker_a_3"),
    b1: synthesize(VOICE_B, TEXTS.b1, "speaker_b_1"),
    b2: synthesize(VOICE_B, TEXTS.b2, "speaker_b_2"),
    b3: synthesize(VOICE_B, TEXTS.b3, "speaker_b_3"),
    c1: synthesize(VOICE_C, TEXTS.b1, "speaker_c_1"),
  };

  // A short (<1.5 s) utterance of speaker A for length-adaptive scoring.
  files.aShort = path.join(dir, "speaker_a_short.wav");
  run(["-i", files.a3, "-t", "1.1", "-c:a", "pcm_s16le", files.aShort, "-y"]);

  // Complete brief utterances ("yes", "okay", "sure") from both speakers: the
  // ticket's false-rejection case. These are ~1.8 s / ~2.3 s respectively.
  files.aYes = path.join(dir, "speaker_a_yes_okay.wav");
  run([
    "-f", "lavfi", "-i", `flite=text='${TEXTS.yes}':voice=${VOICE_A}`,
    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", files.aYes, "-y",
  ]);
  files.bYes = path.join(dir, "speaker_b_yes_okay.wav");
  run([
    "-f", "lavfi", "-i", `flite=text='${TEXTS.yes}':voice=${VOICE_B}`,
    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", files.bYes, "-y",
  ]);

  // A too-short clip for enrollment validation.
  files.aTiny = path.join(dir, "speaker_a_tiny.wav");
  run(["-i", files.a3, "-t", "0.5", "-c:a", "pcm_s16le", files.aTiny, "-y"]);

  // Mixed conversation: A, then B, then A, with 0.8 s pauses between turns.
  files.mixed = path.join(dir, "mixed_a_b_a.wav");
  run([
    "-i", files.a1,
    "-i", files.b1,
    "-i", files.a2,
    "-filter_complex",
    "[0:a]apad=pad_dur=0.8[x0];[1:a]apad=pad_dur=0.8[x1];[x0][x1][2:a]concat=n=3:v=0:a=1[out]",
    "-map", "[out]",
    "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le",
    files.mixed, "-y",
  ]);

  fixtureDir = files;
  return files;
}

/** Per-process isolated database path so tests never share state. */
export function tempDbPath(label = "test") {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `voice-vault-${label}-`));
  return {
    dir,
    db: path.join(dir, "history.db"),
    raw: path.join(dir, "raw"),
  };
}

/** Read a fixture WAV as 16 kHz mono PCM16. */
export function readPcm(file) {
  const decoded = decodeWav(fs.readFileSync(file));
  if (!decoded) throw new Error(`Not a decodable PCM16 WAV: ${file}`);
  return decoded.samples;
}

