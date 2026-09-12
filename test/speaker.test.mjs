import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import {
  DEFAULT_CALIBRATION,
  DEFAULT_DECISION,
  SPEAKER_MODELS,
  calibrateGallery,
  cosineSimilarity,
  createSpeakerEmbedder,
  decideSegment,
  modelPathFor,
  normalizeVector,
  scoreAgainstGallery,
} from "../src/speaker.mjs";
import { fixtures, readPcm, speechFixturesAvailable } from "./helpers/audio.mjs";

const hasFixtures = speechFixturesAvailable();

test("normalizeVector produces unit-length vectors", () => {
  const normalized = normalizeVector(new Float32Array([3, 4]));
  assert.ok(Math.abs(Math.hypot(normalized[0], normalized[1]) - 1) < 1e-6);
  assert.ok(Math.abs(normalized[0] - 0.6) < 1e-6);
  assert.ok(Math.abs(normalized[1] - 0.8) < 1e-6);
  const zero = normalizeVector(new Float32Array([0, 0]));
  assert.deepEqual(Array.from(zero), [0, 0]);
});

test("cosineSimilarity handles identical, orthogonal and unnormalized inputs", () => {
  assert.equal(cosineSimilarity(new Float32Array([1, 0]), new Float32Array([1, 0])), 1);
  assert.equal(cosineSimilarity(new Float32Array([1, 0]), new Float32Array([0, 1])), 0);
  const score = cosineSimilarity(new Float32Array([3, 0]), new Float32Array([10, 0]));
  assert.ok(Math.abs(score - 1) < 1e-9);
  assert.equal(cosineSimilarity(new Float32Array([1, 0]), new Float32Array([1, 0, 0])), 0);
});

test("calibrateGallery self-calibrates mu, sigma and a mu-3sigma threshold", () => {
  // Three unit vectors; mu/sigma must equal the statistics of the gallery's
  // own intra-speaker pairwise cosine similarities.
  const gallery = [
    new Float32Array([1, 0, 0]),
    normalizeVector(new Float32Array([0.8, Math.sqrt(1 - 0.64), 0])),
    normalizeVector(new Float32Array([0.6, 0, Math.sqrt(1 - 0.36)])),
  ];
  const calibration = calibrateGallery(gallery);
  assert.equal(calibration.mode, "self");
  assert.equal(calibration.sampleCount, 3);
  assert.equal(calibration.pairCount, 3);

  const pairs = calibration.pairs;
  const expectedMu = pairs.reduce((sum, value) => sum + value, 0) / pairs.length;
  assert.ok(Math.abs(calibration.mu - expectedMu) < 1e-6, `mu ${calibration.mu}`);
  assert.ok(calibration.mu > 0.55 && calibration.mu < 0.85);

  const expectedSigma = Math.sqrt(
    pairs.reduce((sum, value) => sum + (value - calibration.mu) ** 2, 0) / (pairs.length - 1),
  );
  assert.ok(Math.abs(calibration.sigma - Math.max(expectedSigma, DEFAULT_CALIBRATION.sigmaFloor)) < 1e-6);
  const expectedThreshold = Math.min(
    DEFAULT_CALIBRATION.maxThreshold,
    Math.max(DEFAULT_CALIBRATION.minThreshold, calibration.mu - 3 * calibration.sigma),
  );
  assert.ok(Math.abs(calibration.threshold - expectedThreshold) < 1e-6);
  assert.equal(calibration.strictness, 3);
  assert.equal(calibration.dim, 3);
});

test("a two-clip gallery calibrates from its single pair", () => {
  const gallery = [
    new Float32Array([1, 0, 0]),
    normalizeVector(new Float32Array([0.8, 0.6, 0])),
  ];
  const calibration = calibrateGallery(gallery);
  assert.equal(calibration.mode, "self-single-pair");
  assert.equal(calibration.pairCount, 1);
  assert.equal(calibration.mu, 0.8);
  assert.equal(calibration.sigma, DEFAULT_CALIBRATION.sigmaFloor);
  assert.equal(
    calibration.threshold,
    Number((0.8 - 3 * DEFAULT_CALIBRATION.sigmaFloor).toFixed(6)),
  );
});

test("calibrateGallery enforces the sigma floor and threshold clamp", () => {
  const identical = [
    new Float32Array([1, 0, 0]),
    new Float32Array([1, 0, 0]),
    new Float32Array([1, 0, 0]),
  ];
  const calibration = calibrateGallery(identical);
  assert.equal(calibration.mu, 1);
  assert.equal(calibration.sigma, DEFAULT_CALIBRATION.sigmaFloor);
  assert.ok(calibration.threshold <= DEFAULT_CALIBRATION.maxThreshold);
  assert.ok(calibration.threshold < 1, "a zero-variance gallery must not reject the user");
});

test("calibrateGallery falls back to a prior with a single enrollment clip", () => {
  const calibration = calibrateGallery([new Float32Array([1, 0, 0])]);
  assert.equal(calibration.mode, "prior");
  assert.equal(calibration.pairCount, 0);
  assert.equal(calibration.mu, DEFAULT_CALIBRATION.priorMu);
  assert.equal(
    calibration.threshold,
    Number((DEFAULT_CALIBRATION.priorMu - 3 * DEFAULT_CALIBRATION.priorSigma).toFixed(6)),
  );
});

test("scoreAgainstGallery uses maximum cosine similarity over the gallery", () => {
  const gallery = [
    new Float32Array([1, 0, 0]),
    normalizeVector(new Float32Array([0.5, 0.5, 0])),
  ];
  const scored = scoreAgainstGallery(normalizeVector(new Float32Array([0.9, 0.1, 0])), gallery);
  assert.equal(scored.scores.length, 2);
  assert.equal(scored.best, Math.max(...scored.scores));
  assert.ok(scored.best > 0.9);
});

test("decideSegment rejects a long segment below the calibrated threshold", () => {
  const calibration = { mu: 0.9, sigma: 0.05, threshold: 0.75 };
  const rejected = decideSegment(0.74, 4000, calibration);
  assert.equal(rejected.accepted, false);
  assert.equal(rejected.reason, "below_threshold");
  assert.equal(rejected.shortUtteranceRelaxed, false);

  const accepted = decideSegment(0.76, 4000, calibration);
  assert.equal(accepted.accepted, true);
  assert.equal(accepted.reason, "match");
  assert.ok(Math.abs(accepted.z - -2.8) < 1e-6);
});

test("decideSegment relaxes the threshold for short utterances", () => {
  const calibration = { mu: 0.9, sigma: 0.05, threshold: 0.75 };
  // 500 ms: deficit 5/6 -> relaxation (5/6)*3 sigma = 0.125 -> effective 0.625
  const verdict = decideSegment(0.7, 500, calibration);
  assert.equal(verdict.accepted, true);
  assert.equal(verdict.shortUtteranceRelaxed, true);
  assert.equal(verdict.reason, "match_short_utterance");
  assert.ok(verdict.effectiveThreshold < 0.75);
  assert.ok(verdict.relaxation > 2.4 && verdict.relaxation < 2.6, `relaxation ${verdict.relaxation}`);

  // A long segment with the same score is still rejected.
  assert.equal(decideSegment(0.7, 4000, calibration).accepted, false);

  // A 1.4 s "yes" also gets a small relaxation, an exactly-3 s one does not.
  assert.ok(decideSegment(0.74, 1400, calibration).relaxation > 0);
  assert.equal(decideSegment(0.74, DEFAULT_DECISION.shortUtteranceMs, calibration).relaxation, 0);
});

test("decideSegment relaxes brief words of the enrolled speaker past a near-miss", () => {
  // Realistic CAM++ numbers: a 1.85 s "Yes. Okay. Sure." scores ~0.73 while the
  // calibrated threshold is ~0.74; without relaxation it would be dropped.
  const calibration = { mu: 0.839116, sigma: 0.032736, threshold: 0.740908 };
  const strict = decideSegment(0.73, 5000, calibration);
  assert.equal(strict.accepted, false);
  const relaxed = decideSegment(0.73, 1845, calibration);
  assert.equal(relaxed.accepted, true);
  assert.equal(relaxed.reason, "match_short_utterance");
  assert.ok(relaxed.effectiveThreshold < relaxed.threshold);
});

test("decideSegment keeps the absolute score floor even for very short blips", () => {
  const calibration = { mu: 0.9, sigma: 0.05, threshold: 0.75 };
  const verdict = decideSegment(0.05, 120, calibration);
  assert.equal(verdict.accepted, false);
  assert.ok(verdict.effectiveThreshold >= DEFAULT_DECISION.minAbsoluteScore);
});

test("decideSegment can be configured with an absolute floor above the threshold", () => {
  const calibration = { mu: 0.9, sigma: 0.05, threshold: 0.75 };
  const verdict = decideSegment(0.8, 900, calibration, { minAbsoluteScore: 0.85 });
  assert.equal(verdict.accepted, false);
  assert.equal(verdict.reason, "below_absolute_floor");
});

test("speaker embedder extracts normalized, speaker-discriminative vectors", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const embedder = await createSpeakerEmbedder({ reuse: false });
  try {
    const info = embedder.info();
    assert.ok(["onnx", "dsp"].includes(info.backendFamily));
    assert.equal(info.dim, embedder.dim);
    assert.ok([192, 512].includes(info.dim) || info.backendFamily === "dsp", `dim ${info.dim}`);

    const started = Date.now();
    const [a1, a2, b1] = await embedder.embedBatch(
      [readPcm(files.a1), readPcm(files.a2), readPcm(files.b1)],
      16000,
    );
    const elapsed = Date.now() - started;
    for (const result of [a1, a2, b1]) {
      assert.equal(result.embedding.length, embedder.dim);
      const norm = Math.hypot(...result.embedding);
      assert.ok(Math.abs(norm - 1) < 1e-4, `norm ${norm}`);
      assert.ok(result.embedding.every((value) => Number.isFinite(value)));
    }

    const sameSpeaker = cosineSimilarity(a1.embedding, a2.embedding);
    const differentSpeaker = cosineSimilarity(a1.embedding, b1.embedding);
    assert.ok(
      sameSpeaker > differentSpeaker + 0.15,
      `same ${sameSpeaker} vs different ${differentSpeaker}`,
    );

    console.log(
      `      [speaker] backend=${info.backend} dim=${info.dim} ` +
      `same=${sameSpeaker.toFixed(3)} different=${differentSpeaker.toFixed(3)} ` +
      `batch=${elapsed}ms`,
    );
  } finally {
    await embedder.dispose();
  }
});

test("192-d and 512-d ONNX models both load and embed", { skip: !hasFixtures }, async () => {
  const files = fixtures();
  const embedder = await createSpeakerEmbedder({ reuse: false });
  const info = embedder.info();
  if (info.backendFamily !== "onnx") {
    await embedder.dispose();
    return; // covered by the DSP fallback path above
  }
  await embedder.dispose();

  const dims = new Map();
  for (const [modelId, spec] of Object.entries(SPEAKER_MODELS)) {
    if (!fs.existsSync(modelPathFor(modelId))) continue;
    const modelEmbedder = await createSpeakerEmbedder({ modelId, reuse: false, download: false });
    try {
      const modelInfo = modelEmbedder.info();
      assert.equal(modelInfo.dim, spec.outputDim, `${modelId} dim`);
      const [result] = await modelEmbedder.embedBatch([readPcm(files.a1)], 16000);
      const norm = Math.hypot(...result.embedding);
      assert.ok(Math.abs(norm - 1) < 1e-4);
      dims.set(modelId, modelInfo.dim);
    } finally {
      await modelEmbedder.dispose();
    }
  }
  assert.ok(dims.size >= 1, "expected at least the default model to be installed");
  if (dims.size >= 2) {
    assert.ok([...dims.values()].includes(192) && [...dims.values()].includes(512), [...dims]);
  }
  console.log(`      [speaker] model dimensions: ${JSON.stringify([...dims])}`);
});
