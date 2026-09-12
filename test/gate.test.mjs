import test, { after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { fixtures, readPcm, speechFixturesAvailable, tempDbPath } from "./helpers/audio.mjs";

const hasFixtures = speechFixturesAvailable();
const paths = tempDbPath("gate");
process.env.VOICE_VAULT_DB = paths.db;
process.env.VOICE_VAULT_STORAGE = paths.raw;

const { transcribeAudioFile } = await import("../src/transcriber.mjs");
const { clearVoiceProfile, enrollVoice, loadVoiceProfile } = await import("../src/profile.mjs");
const { DEFAULT_DECISION, createSpeakerEmbedder, disposeSharedEmbedder } = await import("../src/speaker.mjs");
const { decodeWav } = await import("../src/wav.mjs");

after(async () => {
  await disposeSharedEmbedder();
});

function stubTranscriber(calls) {
  return async (wavPath, reqId) => {
    const decoded = decodeWav(fs.readFileSync(wavPath));
    const durationMs = decoded ? (decoded.samples.length / decoded.sampleRate) * 1000 : 0;
    calls.push({ reqId, durationMs });
    return { text: `heard ${Math.round(durationMs)}ms`, transcribe_ms: 1, backend: "stub" };
  };
}

function wavDurationMs(file) {
  const decoded = decodeWav(fs.readFileSync(file));
  return (decoded.samples.length / decoded.sampleRate) * 1000;
}

test("enrollment persists a calibrated voiceprint in SQLite", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const embedder = await createSpeakerEmbedder({ reuse: false });
  try {
    const result = await enrollVoice(
      [readPcm(files.a1), readPcm(files.a2), readPcm(files.a3)],
      { embedder },
    );
    assert.equal(result.profile.enrolled, true);
    assert.equal(result.profile.sampleCount, 3);
    assert.equal(result.profile.gallery.length, 3);
    assert.ok(result.profile.gallery.every((vector) => vector instanceof Float32Array));
    assert.equal(result.calibration.mode, "self");
    assert.equal(result.calibration.pairCount, 3);
    assert.ok(result.profile.threshold < 1 && result.profile.threshold > 0);
    const clampedThreshold = Math.min(
      0.85,
      Math.max(0.2, result.profile.mu - 3 * result.profile.sigma),
    );
    assert.ok(
      Math.abs(result.profile.threshold - clampedThreshold) < 1e-4,
      `threshold ${result.profile.threshold} vs mu-3sigma ${clampedThreshold}`,
    );

    // A fresh read from SQLite must reproduce the same calibration.
    const reloaded = loadVoiceProfile();
    assert.equal(reloaded.threshold, result.profile.threshold);
    assert.equal(reloaded.dim, result.profile.dim);
    assert.equal(reloaded.modelId, result.profile.modelId);
  } finally {
    await embedder.dispose();
  }
});

test("gate keeps the enrolled speaker and drops a different speaker", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const profile = loadVoiceProfile();
  assert.ok(profile, "profile from the enrollment test");

  const keepCalls = [];
  const kept = await transcribeAudioFile(files.a2, "keep", {
    transcribe: stubTranscriber(keepCalls),
    profile,
  });
  assert.equal(kept.gate.enabled, true);
  assert.equal(kept.rejected, false);
  assert.ok(kept.gate.kept >= 1);
  assert.equal(kept.gate.rejected, 0);
  assert.ok(kept.text.length > 0);

  const dropCalls = [];
  const dropped = await transcribeAudioFile(files.b1, "drop", {
    transcribe: stubTranscriber(dropCalls),
    profile,
  });
  assert.equal(dropped.rejected, true);
  assert.equal(dropped.text, "");
  assert.equal(dropped.hasSpeech, false);
  assert.equal(dropped.backend, "speaker_gate_rejected");
  assert.equal(dropped.gate.kept, 0);
  assert.equal(dropped.gate.rejected, 1);
  assert.equal(dropCalls.length, 0, "Whisper must not be called when everything is rejected");
  assert.ok(dropped.gate.segments[0].bestScore < profile.threshold);
});

test("mixed multi-speaker audio keeps only the enrolled speaker's words", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const profile = loadVoiceProfile();
  const calls = [];
  const result = await transcribeAudioFile(files.mixed, "mixed", {
    transcribe: stubTranscriber(calls),
    profile,
  });

  assert.equal(result.gate.enabled, true);
  assert.equal(result.gate.kept, 2, JSON.stringify(result.gate.segments));
  assert.equal(result.gate.rejected, 1);

  const acceptedIndexes = result.gate.segments.filter((segment) => segment.accepted).map((s) => s.index);
  assert.deepEqual(acceptedIndexes, [0, 2]);
  assert.equal(result.gate.segments[1].accepted, false);

  assert.equal(calls.length, 1, "exactly one Whisper call with the filtered audio");
  const expected = wavDurationMs(files.a1) + wavDurationMs(files.a2);
  assert.ok(
    Math.abs(calls[0].durationMs - expected) < 600,
    `whisper heard ${calls[0].durationMs}ms, expected ~${expected}ms`,
  );
  assert.ok(
    calls[0].durationMs < wavDurationMs(files.mixed) - 2000,
    "the other speaker's audio must not reach Whisper",
  );
});

test("short utterances are not falsely rejected", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const profile = loadVoiceProfile();

  // A complete brief utterance ("Yes. Okay. Sure.") scores slightly below the
  // strict calibrated threshold; length-adaptive relaxation must retain it.
  const calls = [];
  const result = await transcribeAudioFile(files.aYes, "short", {
    transcribe: stubTranscriber(calls),
    profile,
  });
  assert.equal(result.rejected, false);
  assert.ok(result.text.length > 0);
  assert.equal(result.gate.kept, 1);
  const [decision] = result.gate.segments;
  assert.ok(decision.durationMs < DEFAULT_DECISION.shortUtteranceMs);
  assert.equal(decision.accepted, true);
  assert.equal(decision.shortUtteranceRelaxed, true);
  assert.ok(decision.effectiveThreshold <= decision.threshold);
  assert.ok(
    ["match", "match_short_utterance"].includes(decision.reason),
    JSON.stringify(decision),
  );

  // A truncated 1.1 s fragment of the enrolled speaker is kept as well.
  const truncated = await transcribeAudioFile(files.aShort, "short-truncated", {
    transcribe: stubTranscriber([]),
    profile,
  });
  assert.equal(truncated.rejected, false);
  assert.equal(truncated.gate.kept, 1);
  assert.equal(truncated.gate.segments[0].accepted, true);

  // The same brief words from another speaker are still rejected.
  const other = await transcribeAudioFile(files.bYes, "short-other", {
    transcribe: stubTranscriber([]),
    profile,
  });
  assert.equal(other.rejected, true);
  assert.equal(other.gate.kept, 0);
  assert.equal(other.gate.segments[0].accepted, false);
});

test("no enrolled profile transcribes everything without gating", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  clearVoiceProfile();
  assert.equal(loadVoiceProfile(), null);

  const calls = [];
  const result = await transcribeAudioFile(files.mixed, "ungated", {
    transcribe: stubTranscriber(calls),
    profile: null,
  });
  assert.equal(result.gate.enabled, false);
  assert.equal(result.gate.reason, "not_enrolled");
  assert.equal(result.rejected, false);
  assert.equal(calls.length, 1);
  assert.ok(Math.abs(calls[0].durationMs - wavDurationMs(files.mixed)) < 400);
});

test("gating can be disabled per request", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const calls = [];
  const result = await transcribeAudioFile(files.a1, "disabled", {
    transcribe: stubTranscriber(calls),
    speakerGate: false,
  });
  assert.equal(result.gate.enabled, false);
  assert.equal(result.gate.reason, "disabled");
  assert.equal(result.rejected, false);
});

test("an incompatible embedder degrades to ungated transcription", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const primary = await createSpeakerEmbedder({ reuse: false });
  try {
    const enrollment = await enrollVoice(
      [readPcm(files.a1), readPcm(files.a2), readPcm(files.a3)],
      { embedder: primary },
    );
    const otherFamily = primary.info().backendFamily === "onnx" ? "dsp" : "onnx";
    let other;
    try {
      other = await createSpeakerEmbedder({ backend: otherFamily, reuse: false, download: false });
    } catch {
      return; // the alternative backend is unavailable in this environment
    }
    try {
      const calls = [];
      const result = await transcribeAudioFile(files.a1, "incompatible", {
        transcribe: stubTranscriber(calls),
        embedder: other,
        profile: enrollment.profile,
      });
      assert.equal(result.gate.enabled, false);
      assert.match(result.gate.reason, /mismatch/);
      assert.equal(result.rejected, false);
      assert.equal(calls.length, 1, "audio still reaches Whisper when gating is unavailable");
    } finally {
      await other.dispose();
    }
  } finally {
    await primary.dispose();
  }
});

test("speaker gating adds well under 60 ms for a typical utterance", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  // Measure with a single warm worker so unrelated test processes cannot
  // contend for CPU while the timing samples are taken.
  await disposeSharedEmbedder();
  const embedder = await createSpeakerEmbedder({ reuse: false });
  try {
    await enrollVoice([readPcm(files.a1), readPcm(files.a2), readPcm(files.a3)], { embedder });
    // Warm the worker so model load and ORT initialisation are excluded.
    await transcribeAudioFile(files.a1, "warm", { transcribe: stubTranscriber([]), embedder });

    const measurements = [];
    for (const id of ["latency-1", "latency-2", "latency-3", "latency-4", "latency-5"]) {
      const result = await transcribeAudioFile(files.a2, id, {
        transcribe: stubTranscriber([]),
        embedder,
      });
      measurements.push(result.gate);
    }
    const gateMs = measurements.map((gate) => gate.gateMs).sort((a, b) => a - b);
    const embedMs = measurements.map((gate) => gate.embedMs).sort((a, b) => a - b);
    const median = gateMs[Math.floor(gateMs.length / 2)];
    const limit = Number(process.env.VOICE_VAULT_LATENCY_LIMIT_MS || 60);
    console.log(
      `      [latency] gate=${gateMs.join("/")}ms median=${median}ms ` +
      `embed=${embedMs.join("/")}ms provider=${measurements[0].embedder?.provider}`,
    );
    assert.ok(median < limit, `median gate latency ${median}ms exceeded ${limit}ms`);
    assert.ok(
      embedMs[Math.floor(embedMs.length / 2)] < limit,
      `median embedding latency exceeded ${limit}ms`,
    );
  } finally {
    await embedder.dispose();
    await disposeSharedEmbedder();
  }
});
