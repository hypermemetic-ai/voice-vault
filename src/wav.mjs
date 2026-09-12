/**
 * PCM16 / WAV helpers shared by the transcription and speaker-gating pipeline.
 *
 * Everything in Voice Vault works on 16 kHz mono 16-bit PCM, which is what
 * ffmpeg produces and what both Whisper and the speaker-embedding models
 * expect.
 */

const RIFF = Buffer.from("RIFF");
const WAVE = Buffer.from("WAVE");
const PCM_FORMAT = 1;

/** View any byte-ish value as a Buffer without copying. */
function asByteBuffer(value) {
  if (Buffer.isBuffer(value)) return value;
  if (ArrayBuffer.isView(value)) {
    return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  }
  return Buffer.from(value ?? []);
}

/** View any int16-ish value as an Int16Array without copying when possible. */
function asInt16(pcm) {
  if (pcm instanceof Int16Array) return pcm;
  if (ArrayBuffer.isView(pcm)) {
    return new Int16Array(pcm.buffer, pcm.byteOffset, Math.floor(pcm.byteLength / 2));
  }
  return Int16Array.from(pcm ?? []);
}

/** Encode raw little-endian PCM16 samples into a canonical mono WAV file. */
export function encodePcm16Wav(pcm, sampleRate = 16000) {
  const data = asByteBuffer(pcm);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + data.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(PCM_FORMAT, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(sampleRate * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36);
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

/**
 * Decode a RIFF/WAVE buffer.
 *
 * Returns `{ sampleRate, channels, samples: Int16Array }` for 16-bit PCM, or
 * `null` for anything we cannot interpret confidently (callers then fall back
 * to treating the audio as opaque, exactly like the existing VAD path).
 */
export function decodeWav(buffer) {
  const wav = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer ?? []);
  if (wav.length < 12 || !wav.subarray(0, 4).equals(RIFF) || !wav.subarray(8, 12).equals(WAVE)) {
    return null;
  }

  let format = null;
  let data = null;
  for (let offset = 12; offset + 8 <= wav.length;) {
    const id = wav.subarray(offset, offset + 4).toString("ascii");
    const size = wav.readUInt32LE(offset + 4);
    const start = offset + 8;
    const end = start + size;
    if (end > wav.length) return null;
    if (id === "fmt " && size >= 16 && !format) {
      format = {
        encoding: wav.readUInt16LE(start),
        channels: wav.readUInt16LE(start + 2),
        sampleRate: wav.readUInt32LE(start + 4),
        bitsPerSample: wav.readUInt16LE(start + 14),
      };
    } else if (id === "data" && !data) {
      data = wav.subarray(start, end);
    }
    offset = end + (size % 2);
  }

  if (
    !format
    || !data
    || format.encoding !== PCM_FORMAT
    || format.bitsPerSample !== 16
    || format.channels < 1
    || data.length < 2
  ) {
    return null;
  }

  let samples;
  if (format.channels === 1) {
    samples = new Int16Array(data.buffer, data.byteOffset, Math.floor(data.length / 2));
  } else {
    const frames = Math.floor(data.length / (2 * format.channels));
    samples = new Int16Array(frames);
    for (let frame = 0; frame < frames; frame += 1) {
      let sum = 0;
      for (let channel = 0; channel < format.channels; channel += 1) {
        sum += data.readInt16LE((frame * format.channels + channel) * 2);
      }
      samples[frame] = Math.round(sum / format.channels);
    }
  }

  return { sampleRate: format.sampleRate, channels: format.channels, samples };
}

/** Convert Int16 PCM to float samples in [-1, 1]. */
export function pcm16ToFloat32(pcm) {
  const samples = asInt16(pcm);
  const out = new Float32Array(samples.length);
  for (let i = 0; i < samples.length; i += 1) out[i] = samples[i] / 32768;
  return out;
}

/** Convert float samples in [-1, 1] to Int16 PCM with clipping. */
export function float32ToPcm16(float32) {
  const out = new Int16Array(float32.length);
  for (let i = 0; i < float32.length; i += 1) {
    const value = Math.max(-1, Math.min(1, float32[i]));
    out[i] = value < 0 ? Math.round(value * 32768) : Math.round(value * 32767);
  }
  return out;
}

/** Concatenate Int16 PCM chunks. */
export function concatPcm16(chunks) {
  const total = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Int16Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.length;
  }
  return out;
}

/** Base64 helpers used by the enroll/verify APIs and the Python sidecar. */
export function pcm16ToBase64(pcm) {
  return asByteBuffer(pcm).toString("base64");
}

export function base64ToPcm16(value) {
  const buffer = Buffer.from(String(value ?? ""), "base64");
  const copy = new Uint8Array(buffer.length);
  copy.set(buffer);
  return new Int16Array(copy.buffer, 0, Math.floor(copy.length / 2));
}

/** Encode a JS Float32Array as a little-endian BLOB for SQLite storage. */
export function float32ToBlob(vector) {
  const out = Buffer.alloc(vector.length * 4);
  for (let i = 0; i < vector.length; i += 1) out.writeFloatLE(vector[i], i * 4);
  return out;
}

/** Decode a SQLite BLOB (Buffer or Uint8Array) back into a Float32Array. */
export function blobToFloat32(blob) {
  const buffer = Buffer.isBuffer(blob)
    ? blob
    : Buffer.from(blob.buffer, blob.byteOffset, blob.byteLength);
  const out = new Float32Array(Math.floor(buffer.length / 4));
  for (let i = 0; i < out.length; i += 1) out[i] = buffer.readFloatLE(i * 4);
  return out;
}
