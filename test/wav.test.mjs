import test from "node:test";
import assert from "node:assert/strict";
import {
  base64ToPcm16,
  blobToFloat32,
  concatPcm16,
  decodeWav,
  encodePcm16Wav,
  float32ToBlob,
  float32ToPcm16,
  pcm16ToBase64,
  pcm16ToFloat32,
} from "../src/wav.mjs";

test("WAV encode/decode round-trips PCM16 samples", () => {
  const samples = new Int16Array([0, 1, -1, 32767, -32768, 1234, -4321]);
  const wav = encodePcm16Wav(samples, 16000);
  assert.equal(wav.subarray(0, 4).toString(), "RIFF");
  const decoded = decodeWav(wav);
  assert.equal(decoded.sampleRate, 16000);
  assert.equal(decoded.channels, 1);
  assert.deepEqual(Array.from(decoded.samples), Array.from(samples));
});

test("decodeWav downmixes stereo and rejects non-PCM data", () => {
  // Two stereo frames: (1000, 3000) and (-1000, 1000).
  const data = Buffer.alloc(8);
  data.writeInt16LE(1000, 0);
  data.writeInt16LE(3000, 2);
  data.writeInt16LE(-1000, 4);
  data.writeInt16LE(1000, 6);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(2, 22);
  header.writeUInt32LE(16000, 24);
  header.writeUInt32LE(16000 * 4, 28);
  header.writeUInt16LE(4, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(data.length, 40);

  const decoded = decodeWav(Buffer.concat([header, data]));
  assert.equal(decoded.channels, 2);
  assert.deepEqual(Array.from(decoded.samples), [2000, 0]);
  assert.equal(decodeWav(Buffer.from("not a wav file at all")), null);
});

test("float/int16 and base64 helpers are lossless enough for the models", () => {
  const floats = new Float32Array([0, 0.5, -0.5, 0.999, -1]);
  const pcm = float32ToPcm16(floats);
  const back = pcm16ToFloat32(pcm);
  for (let i = 0; i < floats.length; i += 1) {
    assert.ok(Math.abs(back[i] - floats[i]) < 0.001, `sample ${i} drifted`);
  }

  const base64 = pcm16ToBase64(pcm);
  assert.deepEqual(Array.from(base64ToPcm16(base64)), Array.from(pcm));

  const concatenated = concatPcm16([new Int16Array([1, 2]), new Int16Array([3])]);
  assert.deepEqual(Array.from(concatenated), [1, 2, 3]);
});

test("float32 BLOBs round-trip through SQLite storage format", () => {
  const vector = new Float32Array([0.25, -0.5, 1, 0]);
  const blob = float32ToBlob(vector);
  assert.equal(blob.length, vector.length * 4);
  const back = blobToFloat32(new Uint8Array(blob));
  assert.deepEqual(Array.from(back), Array.from(vector));
});
