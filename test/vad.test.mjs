import test from "node:test";
import assert from "node:assert/strict";
import { concatenateSegments, frameLevelsDb, segmentSpeech } from "../src/vad.mjs";

const SAMPLE_RATE = 16000;

/** Speech-ish burst: amplitude-modulated harmonic tone. */
function burst(seconds, amplitude = 6000, frequency = 180) {
  const count = Math.round(seconds * SAMPLE_RATE);
  const samples = new Int16Array(count);
  for (let i = 0; i < count; i += 1) {
    const t = i / SAMPLE_RATE;
    const envelope = 0.55 + 0.45 * Math.sin(2 * Math.PI * 3.1 * t);
    const value =
      Math.sin(2 * Math.PI * frequency * t) * 0.7 +
      Math.sin(2 * Math.PI * frequency * 2 * t) * 0.3;
    samples[i] = Math.round(value * envelope * amplitude);
  }
  return samples;
}

function silence(seconds) {
  return new Int16Array(Math.round(seconds * SAMPLE_RATE));
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

test("frameLevelsDb reports dBFS levels per hop", () => {
  const { levels } = frameLevelsDb(burst(0.5), SAMPLE_RATE);
  assert.ok(levels.length > 40);
  assert.ok(levels.every((level) => level <= 0 && level >= -100));
});

test("segmentSpeech finds each speech burst between silences", () => {
  const pcm = concat([silence(0.5), burst(1.0), silence(0.7), burst(0.8), silence(0.6)]);
  const result = segmentSpeech(pcm, SAMPLE_RATE);
  assert.equal(result.segments.length, 2, JSON.stringify(result.segments));

  const [first, second] = result.segments;
  assert.ok(Math.abs(first.startMs - 500) < 180, `first start ${first.startMs}`);
  assert.ok(Math.abs(first.endMs - 1500) < 220, `first end ${first.endMs}`);
  assert.ok(Math.abs(second.startMs - 2200) < 200, `second start ${second.startMs}`);
  assert.ok(Math.abs(second.endMs - 3000) < 260, `second end ${second.endMs}`);
  assert.ok(first.durationMs > 800 && first.durationMs < 1300);
  assert.ok(result.noiseFloorDb < -60, `noise floor ${result.noiseFloorDb}`);
  assert.ok(result.speechRatio > 0.4 && result.speechRatio < 0.8);
});

test("segmentSpeech returns nothing for pure silence", () => {
  const result = segmentSpeech(silence(2.5), SAMPLE_RATE);
  assert.deepEqual(result.segments, []);
  assert.equal(result.speechRatio, 0);
});

test("segmentSpeech collapses continuous loud audio into one full-span segment", () => {
  const result = segmentSpeech(burst(3.0, 9000), SAMPLE_RATE);
  assert.equal(result.segments.length, 1);
  assert.equal(result.fullSpan, true);
  assert.ok(result.segments[0].durationMs > 2900);
});

test("segmentSpeech ignores isolated clicks", () => {
  const click = new Int16Array(SAMPLE_RATE * 0.01);
  click.fill(20000);
  const pcm = concat([silence(1), click, silence(1)]);
  const result = segmentSpeech(pcm, SAMPLE_RATE);
  assert.equal(result.segments.length, 0);
});

test("concatenateSegments stitches only the accepted regions", () => {
  const pcm = concat([silence(0.5), burst(0.5), silence(0.5), burst(0.5), silence(0.5)]);
  const result = segmentSpeech(pcm, SAMPLE_RATE);
  assert.equal(result.segments.length, 2);
  const onlyFirst = concatenateSegments(pcm, [result.segments[0]]);
  assert.equal(onlyFirst.length, result.segments[0].endSample - result.segments[0].startSample);
  const both = concatenateSegments(pcm, result.segments);
  assert.equal(
    both.length,
    result.segments.reduce((sum, segment) => sum + (segment.endSample - segment.startSample), 0),
  );
  assert.ok(both.length < pcm.length);
});
