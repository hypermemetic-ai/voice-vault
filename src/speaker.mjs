/**
 * Server-side speaker verification ("acoustic voiceprint gate").
 *
 * Pipeline:
 *   1. `OnnxSpeakerEmbedder` runs an ONNX speaker-embedding model (3D-Speaker
 *      CAM++ / WeSpeaker, 192-d or 512-d, whichever the model metadata says)
 *      through `onnxruntime` in a persistent Python sidecar. The sidecar is
 *      optional: when onnxruntime/the model are unavailable the module falls
 *      back to `DspSpeakerEmbedder`, a dependency-free log-mel statistics
 *      embedder, so dictation keeps working (ungated) on bare installs.
 *   2. `calibrateGallery` self-calibrates the acceptance threshold from
 *      intra-speaker pairwise cosine similarity of the enrollment clips
 *      (mu and sigma, threshold = mu - strictness * sigma).
 *   3. `scoreAgainstGallery` + `decideSegment` score each VAD segment against
 *      the gallery and apply length-adaptive relaxation so short utterances
 *      ("yes", "okay") are not falsely rejected.
 */

import { spawn, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.join(__dirname, "..");
export const SPEAKER_WORKER_PATH = path.join(REPO_ROOT, "python", "speaker_worker.py");

const MODEL_RELEASE_BASE =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models";

/**
 * Known-good ONNX speaker embedding models. `outputDim` is informational: the
 * real dimension always comes from the model metadata at load time.
 */
export const SPEAKER_MODELS = Object.freeze({
  "campplus-en-voxceleb-512": Object.freeze({
    id: "campplus-en-voxceleb-512",
    label: "3D-Speaker CAM++ · English VoxCeleb",
    file: "3dspeaker_campplus_en_voxceleb_16k.onnx",
    url: `${MODEL_RELEASE_BASE}/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`,
    sha256: "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
    outputDim: 512,
    framework: "3d-speaker",
    language: "en",
  }),
  "campplus-zh-cn-common-192": Object.freeze({
    id: "campplus-zh-cn-common-192",
    label: "3D-Speaker CAM++ · zh-cn common (192-d)",
    file: "3dspeaker_campplus_zh-cn_16k_common.onnx",
    url: `${MODEL_RELEASE_BASE}/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`,
    sha256: "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11",
    outputDim: 192,
    framework: "3d-speaker",
    language: "zh-cn",
  }),
  "wespeaker-en-voxceleb-campplus-512": Object.freeze({
    id: "wespeaker-en-voxceleb-campplus-512",
    label: "WeSpeaker CAM++ · English VoxCeleb",
    file: "wespeaker_en_voxceleb_campplus.onnx",
    url: `${MODEL_RELEASE_BASE}/wespeaker_en_voxceleb_CAM++.onnx`,
    sha256: "c46fad10b5f81e1aa4a60c162714208577093655076c5450f8c469e522ec54ef",
    outputDim: 512,
    framework: "wespeaker",
    language: "en",
  }),
});

export const DEFAULT_SPEAKER_MODEL =
  process.env.VOICE_VAULT_SPEAKER_MODEL_ID || "campplus-en-voxceleb-512";

export function modelDir() {
  return process.env.VOICE_VAULT_MODEL_DIR || path.join(REPO_ROOT, "models");
}

export function resolveModelSpec(modelId = DEFAULT_SPEAKER_MODEL) {
  const spec = SPEAKER_MODELS[modelId];
  if (!spec) {
    throw new Error(
      `Unknown speaker model '${modelId}'. Known: ${Object.keys(SPEAKER_MODELS).join(", ")}`,
    );
  }
  return spec;
}

export function modelPathFor(modelId = DEFAULT_SPEAKER_MODEL) {
  const explicit = process.env.VOICE_VAULT_SPEAKER_MODEL;
  if (explicit) return explicit;
  return path.join(modelDir(), resolveModelSpec(modelId).file);
}

export async function sha256File(filePath) {
  const hash = crypto.createHash("sha256");
  await new Promise((resolve, reject) => {
    const stream = fs.createReadStream(filePath);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", resolve);
    stream.on("error", reject);
  });
  return hash.digest("hex");
}

/**
 * Make sure the ONNX model exists locally, downloading + verifying it once when
 * allowed. Returns the resolved path (which may not exist when downloads are
 * disabled - callers degrade gracefully).
 */
export async function ensureSpeakerModel(modelId = DEFAULT_SPEAKER_MODEL, options = {}) {
  const spec = resolveModelSpec(modelId);
  const target = options.path || modelPathFor(modelId);
  if (fs.existsSync(target) && fs.statSync(target).size > 0) return target;

  const allowDownload =
    options.download ?? process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD !== "0";
  if (!allowDownload) return target;

  fs.mkdirSync(path.dirname(target), { recursive: true });
  const response = await fetch(spec.url);
  if (!response.ok) {
    throw new Error(`Failed to download speaker model ${spec.url}: HTTP ${response.status}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  const digest = crypto.createHash("sha256").update(bytes).digest("hex");
  if (spec.sha256 && digest !== spec.sha256) {
    throw new Error(
      `Speaker model checksum mismatch for ${spec.file}: expected ${spec.sha256}, got ${digest}`,
    );
  }
  const temp = `${target}.${process.pid}.tmp`;
  fs.writeFileSync(temp, bytes);
  fs.renameSync(temp, target);
  return target;
}

/** Locate a Python interpreter that can import onnxruntime + numpy. */
export function findPython(options = {}) {
  const provider = options.provider || process.env.VOICE_VAULT_SPEAKER_PROVIDER || "cpu";
  const wantsGpu = provider === "cuda" || provider === "tensorrt";
  const venvNames = wantsGpu ? ["venv-gpu", "venv"] : ["venv", "venv-gpu"];
  const candidates = [
    process.env.VOICE_VAULT_PYTHON,
    path.join(REPO_ROOT, ".venv", "bin", "python"),
    ...venvNames.map((name) =>
      path.join(os.homedir(), ".cache", "voice-vault", name, "bin", "python"),
    ),
    "python3",
    "/usr/bin/python3",
  ].filter(Boolean);

  for (const candidate of candidates) {
    if (candidate.includes(path.sep) && !fs.existsSync(candidate)) continue;
    if (pythonSupportsOnnxRuntime(candidate, provider)) return candidate;
  }
  return null;
}

const pythonProbeCache = new Map();

function pythonSupportsOnnxRuntime(pythonPath, provider = "cpu") {
  const cacheKey = `${pythonPath}:${provider}`;
  if (pythonProbeCache.has(cacheKey)) return pythonProbeCache.get(cacheKey);
  const wantsCuda = provider === "cuda" || provider === "tensorrt";
  const probe = wantsCuda
    ? "import onnxruntime as o\n" +
      "try:\n    o.preload_dlls()\nexcept Exception:\n    pass\n" +
      `assert 'CUDAExecutionProvider' in o.get_available_providers()\n`
    : "import onnxruntime, numpy";
  const result = spawnSync(pythonPath, ["-c", probe], {
    timeout: 60000,
    stdio: "ignore",
  });
  const ok = result.status === 0;
  pythonProbeCache.set(cacheKey, ok);
  return ok;
}

function l2Normalize(vector) {
  let sum = 0;
  for (let i = 0; i < vector.length; i += 1) sum += vector[i] * vector[i];
  const norm = Math.sqrt(sum);
  const out = new Float32Array(vector.length);
  if (!Number.isFinite(norm) || norm <= 0) {
    out.set(vector);
    return out;
  }
  for (let i = 0; i < vector.length; i += 1) out[i] = vector[i] / norm;
  return out;
}

export function normalizeVector(vector) {
  return l2Normalize(vector instanceof Float32Array ? vector : Float32Array.from(vector ?? []));
}

export function cosineSimilarity(a, b) {
  if (!a || !b || a.length !== b.length || a.length === 0) return 0;
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i += 1) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  if (normA <= 0 || normB <= 0) return 0;
  return dot / Math.sqrt(normA * normB);
}

// ---------------------------------------------------------------------------
// Python / onnxruntime sidecar
// ---------------------------------------------------------------------------

export class OnnxSpeakerEmbedder {
  constructor(options = {}) {
    this.modelId = options.modelId || DEFAULT_SPEAKER_MODEL;
    this.spec = resolveModelSpec(this.modelId);
    this.modelPath = options.modelPath || modelPathFor(this.modelId);
    this.pythonPath = options.pythonPath || null;
    this.provider = options.provider || process.env.VOICE_VAULT_SPEAKER_PROVIDER || "cpu";
    this.threads = Number(options.threads || process.env.VOICE_VAULT_SPEAKER_THREADS || 4);
    this.timeoutMs = Number(options.timeoutMs || 60000);
    this.workerPath = options.workerPath || SPEAKER_WORKER_PATH;
    this.backendFamily = "onnx";
    this.backend = "onnxruntime";
    this.dim = this.spec.outputDim || 512;
    this.worker = null;
    this.workerInfo = null;
    this.stderrTail = "";
    this.sequence = 0;
    this.pending = new Map();
  }

  async ready() {
    if (this.workerInfo) return this.workerInfo;
    if (!fs.existsSync(this.modelPath)) {
      throw new Error(
        `Speaker model not found at ${this.modelPath}. Run 'npm run speaker:fetch-model' to download it.`,
      );
    }
    const pythonPath = this.pythonPath || findPython({ provider: this.provider });
    if (!pythonPath) {
      throw new Error("No Python interpreter with onnxruntime + numpy was found");
    }
    this.pythonPath = pythonPath;
    this.workerInfo = await this.#startWorker();
    this.dim = this.workerInfo.dim;
    this.backend = `onnxruntime:${this.workerInfo.provider}`;
    return this.workerInfo;
  }

  #startWorker() {
    return new Promise((resolve, reject) => {
      const child = spawn(
        this.pythonPath,
        [
          this.workerPath,
          "--model", this.modelPath,
          "--provider", this.provider,
          "--threads", String(this.threads),
          "--warmup",
        ],
        { stdio: ["pipe", "pipe", "pipe"] },
      );
      this.worker = child;
      this.stderrTail = "";

      let buffer = "";
      let settled = false;
      const timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        try { child.kill("SIGKILL"); } catch {}
        reject(new Error(`Speaker worker startup timed out after ${this.timeoutMs} ms`));
      }, this.timeoutMs);
      timer.unref?.();

      child.stdout.setEncoding("utf8");
      child.stdout.on("data", (chunk) => {
        buffer += chunk;
        let newline;
        while ((newline = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, newline).trim();
          buffer = buffer.slice(newline + 1);
          if (!line) continue;
          let message;
          try {
            message = JSON.parse(line);
          } catch {
            continue;
          }
          if (message.event === "ready" && !settled) {
            settled = true;
            clearTimeout(timer);
            resolve(message);
            continue;
          }
          if (message.event === "error" && !settled) {
            settled = true;
            clearTimeout(timer);
            reject(new Error(`Speaker worker failed to start: ${message.error}`));
            continue;
          }
          if (message.event === "ready") continue;
          this.#handleMessage(message);
        }
      });

      child.stderr.setEncoding("utf8");
      child.stderr.on("data", (chunk) => {
        this.stderrTail = `${this.stderrTail}${chunk}`.slice(-4000);
      });

      child.on("error", (error) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(error);
        }
        this.#handleExit(error);
      });
      child.on("exit", (code, signal) => {
        if (!settled) {
          settled = true;
          clearTimeout(timer);
          reject(
            new Error(
              `Speaker worker exited during startup (code ${code}, signal ${signal}): ${this.stderrTail}`,
            ),
          );
        }
        this.#handleExit(new Error(`Speaker worker exited (code ${code}, signal ${signal})`));
      });
    });
  }

  #handleMessage(message) {
    const entry = this.pending.get(message.id);
    if (!entry) return;
    this.pending.delete(message.id);
    clearTimeout(entry.timer);
    if (message.ok) entry.resolve(message);
    else entry.reject(new Error(message.error || "speaker worker error"));
  }

  #handleExit(error) {
    this.worker = null;
    this.workerInfo = null;
    for (const [, entry] of this.pending) {
      clearTimeout(entry.timer);
      entry.reject(error);
    }
    this.pending.clear();
  }

  #request(payload) {
    const id = (this.sequence += 1);
    const message = JSON.stringify({ id, ...payload });
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`Speaker worker request timed out after ${this.timeoutMs} ms`));
      }, this.timeoutMs);
      timer.unref?.();
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.worker.stdin.write(`${message}\n`);
      } catch (error) {
        this.pending.delete(id);
        clearTimeout(timer);
        reject(error);
      }
    });
  }

  async embedBatch(clips, sampleRate = 16000) {
    await this.ready();
    const started = Date.now();
    const payload = clips.map((clip) => Buffer.from(clip.buffer, clip.byteOffset, clip.byteLength).toString("base64"));
    const response = await this.#request({
      cmd: "embed_batch",
      clips: payload,
      sampleRate,
    });
    const roundTripMs = Date.now() - started;
    const embeddings = (response.embeddings || []).map(
      (values) => normalizeVector(Float32Array.from(values)),
    );
    return embeddings.map((embedding) => ({
      embedding,
      dim: embedding.length,
      backend: this.backend,
      inferenceMs: response.ms / Math.max(1, embeddings.length),
      roundTripMs: roundTripMs / Math.max(1, embeddings.length),
    }));
  }

  async embed(clip, sampleRate = 16000) {
    const [result] = await this.embedBatch([clip], sampleRate);
    return result;
  }

  info() {
    return {
      backendFamily: this.backendFamily,
      backend: this.backend,
      dim: this.dim,
      modelId: this.modelId,
      modelLabel: this.spec.label,
      modelFile: path.basename(this.modelPath),
      provider: this.workerInfo?.provider || this.provider,
      fbank: this.workerInfo?.fbank || null,
      framework: this.workerInfo?.framework || this.spec.framework,
      sampleRate: this.workerInfo?.sampleRate || 16000,
      loadMs: this.workerInfo?.loadMs ?? null,
      python: this.pythonPath,
    };
  }

  async dispose() {
    const child = this.worker;
    this.worker = null;
    this.workerInfo = null;
    if (!child) return;
    try {
      child.stdin.write(`${JSON.stringify({ id: -1, cmd: "shutdown" })}\n`);
    } catch {}
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        try { child.kill("SIGKILL"); } catch {}
        resolve();
      }, 1500);
      timer.unref?.();
      child.once("exit", () => {
        clearTimeout(timer);
        resolve();
      });
    });
  }
}

// ---------------------------------------------------------------------------
// Dependency-free DSP fallback embedder (192-d log-mel statistics)
// ---------------------------------------------------------------------------

const DSP_DIM = 192;
const DSP_MEL_BINS = DSP_DIM / 3;

function hannWindow(size) {
  const window = new Float32Array(size);
  for (let i = 0; i < size; i += 1) window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / size);
  return window;
}

function fftInPlace(real, imag) {
  const n = real.length;
  for (let i = 1, j = 0; i < n; i += 1) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) {
      const tr = real[i]; real[i] = real[j]; real[j] = tr;
      const ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
    }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const angle = (-2 * Math.PI) / len;
    const baseReal = Math.cos(angle);
    const baseImag = Math.sin(angle);
    for (let start = 0; start < n; start += len) {
      let cos = 1;
      let sin = 0;
      for (let k = 0; k < len / 2; k += 1) {
        const evenReal = real[start + k];
        const evenImag = imag[start + k];
        const oddReal = real[start + k + len / 2] * cos - imag[start + k + len / 2] * sin;
        const oddImag = real[start + k + len / 2] * sin + imag[start + k + len / 2] * cos;
        real[start + k] = evenReal + oddReal;
        imag[start + k] = evenImag + oddImag;
        real[start + k + len / 2] = evenReal - oddReal;
        imag[start + k + len / 2] = evenImag - oddImag;
        const nextCos = cos * baseReal - sin * baseImag;
        sin = cos * baseImag + sin * baseReal;
        cos = nextCos;
      }
    }
  }
}

function hzToMel(freq) {
  return 1127 * Math.log(1 + freq / 700);
}

const dspFilterbankCache = new Map();

function dspFilterbank(sampleRate, fftSize, numBins) {
  const key = `${sampleRate}:${fftSize}:${numBins}`;
  if (dspFilterbankCache.has(key)) return dspFilterbankCache.get(key);
  const numFftBins = fftSize / 2;
  const binWidth = sampleRate / fftSize;
  const lowMel = hzToMel(20);
  const highMel = hzToMel(sampleRate / 2 - 400);
  const delta = (highMel - lowMel) / (numBins + 1);
  const filters = [];
  for (let bin = 0; bin < numBins; bin += 1) {
    const left = lowMel + bin * delta;
    const center = lowMel + (bin + 1) * delta;
    const right = lowMel + (bin + 2) * delta;
    const weights = new Float32Array(numFftBins);
    for (let i = 0; i < numFftBins; i += 1) {
      const mel = hzToMel(i * binWidth);
      if (mel > left && mel < right) {
        weights[i] = mel <= center ? (mel - left) / (center - left) : (right - mel) / (right - center);
      }
    }
    filters.push(weights);
  }
  dspFilterbankCache.set(key, filters);
  return filters;
}

/**
 * Deterministic, dependency-free speaker-ish embedding used when the ONNX
 * runtime is unavailable. Log-mel spectral envelope statistics capture vocal
 * tract shape (mean), its variability (std) and its dynamics (delta), which is
 * enough to separate voices well, though it is not a trained speaker model.
 */
export function dspEmbedding(pcm16, sampleRate = 16000) {
  const samples = pcm16 instanceof Int16Array ? pcm16 : Int16Array.from(pcm16 ?? []);
  const frameLength = Math.round(sampleRate * 0.025);
  const frameShift = Math.round(sampleRate * 0.01);
  const fftSize = 512;
  const window = hannWindow(frameLength);
  const filters = dspFilterbank(sampleRate, fftSize, DSP_MEL_BINS);

  const frames = [];
  const real = new Float64Array(fftSize);
  const imag = new Float64Array(fftSize);
  for (let start = 0; start + frameLength <= samples.length; start += frameShift) {
    for (let i = 0; i < frameLength; i += 1) {
      real[i] = (samples[start + i] / 32768) * window[i];
      imag[i] = 0;
    }
    for (let i = frameLength; i < fftSize; i += 1) {
      real[i] = 0;
      imag[i] = 0;
    }
    fftInPlace(real, imag);
    const mel = new Float32Array(DSP_MEL_BINS);
    for (let bin = 0; bin < DSP_MEL_BINS; bin += 1) {
      const weights = filters[bin];
      let energy = 1e-10;
      for (let i = 0; i < weights.length; i += 1) {
        if (weights[i] === 0) continue;
        energy += weights[i] * (real[i] * real[i] + imag[i] * imag[i]);
      }
      mel[bin] = Math.log(energy);
    }
    // Per-frame cepstral mean normalisation removes channel/level effects so
    // the vector describes vocal-tract shape rather than loudness.
    let mean = 0;
    for (let i = 0; i < DSP_MEL_BINS; i += 1) mean += mel[i];
    mean /= DSP_MEL_BINS;
    for (let i = 0; i < DSP_MEL_BINS; i += 1) mel[i] -= mean;
    frames.push(mel);
  }

  const embedding = new Float32Array(DSP_DIM);
  if (frames.length === 0) return embedding;
  for (let bin = 0; bin < DSP_MEL_BINS; bin += 1) {
    let mean = 0;
    for (const frame of frames) mean += frame[bin];
    mean /= frames.length;
    let variance = 0;
    for (const frame of frames) variance += (frame[bin] - mean) ** 2;
    variance /= frames.length;
    embedding[bin] = mean;
    embedding[DSP_MEL_BINS + bin] = Math.sqrt(variance);
    let delta = 0;
    for (let i = 1; i < frames.length; i += 1) delta += Math.abs(frames[i][bin] - frames[i - 1][bin]);
    embedding[2 * DSP_MEL_BINS + bin] = frames.length > 1 ? delta / (frames.length - 1) : 0;
  }
  return l2Normalize(embedding);
}

export class DspSpeakerEmbedder {
  constructor(options = {}) {
    this.backendFamily = "dsp";
    this.backend = "dsp-logmel";
    this.dim = DSP_DIM;
    this.modelId = options.modelId || "dsp-logmel-192";
    this.spec = {
      id: this.modelId,
      label: "Built-in log-mel voiceprint (no ONNX runtime)",
      framework: "dsp",
    };
  }

  async ready() {
    return this.info();
  }

  async embedBatch(clips, sampleRate = 16000) {
    return clips.map((clip) => {
      const started = Date.now();
      const embedding = dspEmbedding(clip, sampleRate);
      return {
        embedding,
        dim: embedding.length,
        backend: this.backend,
        inferenceMs: Date.now() - started,
        roundTripMs: Date.now() - started,
      };
    });
  }

  async embed(clip, sampleRate = 16000) {
    const [result] = await this.embedBatch([clip], sampleRate);
    return result;
  }

  info() {
    return {
      backendFamily: this.backendFamily,
      backend: this.backend,
      dim: this.dim,
      modelId: this.modelId,
      modelLabel: this.spec.label,
      modelFile: null,
      provider: "javascript",
      fbank: "js-fft",
      framework: "dsp",
      sampleRate: 16000,
      loadMs: 0,
      python: null,
    };
  }

  async dispose() {}
}

let sharedEmbedder = null;

/**
 * Create (or reuse) the best available embedder.
 *
 * backend: "auto" (ONNX then DSP), "onnx" (must use ONNX), "dsp" (force fallback).
 */
export async function createSpeakerEmbedder(options = {}) {
  const backend = options.backend || process.env.VOICE_VAULT_SPEAKER_BACKEND || "auto";
  if (backend === "dsp") return new DspSpeakerEmbedder(options);

  const reusable =
    options.reuse !== false &&
    sharedEmbedder &&
    (backend === "auto" || sharedEmbedder.backendFamily === "onnx") &&
    (!options.modelId || sharedEmbedder.modelId === options.modelId);
  if (reusable) return sharedEmbedder;

  const embedder = new OnnxSpeakerEmbedder(options);
  try {
    await ensureSpeakerModel(embedder.modelId, {
      download: options.download ?? process.env.VOICE_VAULT_SPEAKER_AUTODOWNLOAD !== "0",
      path: options.modelPath,
    });
    await embedder.ready();
    if (options.reuse !== false) sharedEmbedder = embedder;
    return embedder;
  } catch (error) {
    await embedder.dispose().catch(() => {});
    if (backend === "onnx") throw error;
    const fallback = new DspSpeakerEmbedder(options);
    fallback.fallbackReason = error.message;
    if (options.reuse !== false) sharedEmbedder = fallback;
    return fallback;
  }
}

export async function disposeSharedEmbedder() {
  if (sharedEmbedder) {
    const embedder = sharedEmbedder;
    sharedEmbedder = null;
    await embedder.dispose().catch(() => {});
  }
}

// ---------------------------------------------------------------------------
// Calibration + decision
// ---------------------------------------------------------------------------

export const DEFAULT_CALIBRATION = Object.freeze({
  /** Ticket requirement: accept at mu - 3 sigma of intra-speaker similarity. */
  strictness: 3,
  /**
   * A handful of enrollment clips recorded in one session underestimates
   * real-world intra-speaker variance (morning voice, fatigue, mic position),
   * which would make mu - 3 sigma unrealistically strict. Floor sigma at 0.05
   * cosine so calibration with 3 near-identical clips stays usable.
   */
  sigmaFloor: 0.05,
  minThreshold: 0.2,
  maxThreshold: 0.85,
  /** Used only when a single enrollment clip makes self-calibration impossible. */
  priorMu: 0.6,
  priorSigma: 0.1,
});

export const DEFAULT_DECISION = Object.freeze({
  /** Below this, a segment is rejected no matter how short it is. */
  minAbsoluteScore: 0.15,
  /**
   * Reference duration for length-adaptive relaxation: segments at or above
   * this length are judged against the strict calibrated threshold, shorter
   * ones get progressively more slack because their embeddings are noisier.
   */
  shortUtteranceMs: 3000,
  /** Maximum relaxation (in sigma) applied to a zero-length utterance. */
  shortUtteranceBoostSigmas: 3,
});

/**
 * Self-calibrate an acceptance threshold from enrollment embeddings.
 *
 * `mu` / `sigma` are the mean and sample standard deviation of all
 * intra-speaker pairwise cosine similarities inside the gallery, and the
 * threshold is `mu - strictness * sigma`.
 */
export function calibrateGallery(embeddings, options = {}) {
  const settings = { ...DEFAULT_CALIBRATION, ...options };
  const gallery = (embeddings || [])
    .map((values) => normalizeVector(values instanceof Float32Array ? values : Float32Array.from(values)))
    .filter((values) => values.length > 0);

  const dim = gallery[0]?.length || 0;
  const pairs = [];
  for (let i = 0; i < gallery.length; i += 1) {
    for (let j = i + 1; j < gallery.length; j += 1) {
      pairs.push(cosineSimilarity(gallery[i], gallery[j]));
    }
  }

  let mu;
  let sigma;
  let mode;
  if (pairs.length >= 2) {
    mu = pairs.reduce((sum, value) => sum + value, 0) / pairs.length;
    const variance =
      pairs.reduce((sum, value) => sum + (value - mu) ** 2, 0) / (pairs.length - 1);
    sigma = Math.sqrt(Math.max(0, variance));
    mode = "self";
  } else if (pairs.length === 1) {
    mu = pairs[0];
    sigma = settings.sigmaFloor;
    mode = "self-single-pair";
  } else {
    mu = settings.priorMu;
    sigma = settings.priorSigma;
    mode = "prior";
  }

  const rawSigma = sigma;
  sigma = Math.max(settings.sigmaFloor, sigma);
  const rawThreshold = mu - settings.strictness * sigma;
  const threshold = Math.min(
    settings.maxThreshold,
    Math.max(settings.minThreshold, rawThreshold),
  );

  return {
    mu: Number(mu.toFixed(6)),
    sigma: Number(sigma.toFixed(6)),
    rawSigma: Number(rawSigma.toFixed(6)),
    threshold: Number(threshold.toFixed(6)),
    mode,
    strictness: settings.strictness,
    sigmaFloor: settings.sigmaFloor,
    dim,
    sampleCount: gallery.length,
    pairCount: pairs.length,
    pairs: pairs.map((value) => Number(value.toFixed(6))),
  };
}

/** Maximum-cosine score of one embedding against an enrolled gallery. */
export function scoreAgainstGallery(embedding, gallery) {
  const scores = (gallery || []).map((reference) => cosineSimilarity(embedding, reference));
  if (scores.length === 0) return { best: 0, mean: 0, scores: [] };
  const best = Math.max(...scores);
  const mean = scores.reduce((sum, value) => sum + value, 0) / scores.length;
  return { best, mean, scores };
}

/**
 * Length-adaptive accept/reject decision.
 *
 * Short segments produce noisier embeddings, so their similarity distribution
 * is wider: the effective threshold is relaxed linearly from the calibrated
 * threshold at `shortUtteranceMs` down to
 * `threshold - shortUtteranceBoostSigmas * sigma` at zero duration, while never
 * dropping below the hard `minAbsoluteScore` floor. Another voice still lands
 * far below the relaxed threshold, so target-speaker rejection holds.
 */
export function decideSegment(score, durationMs, calibration, options = {}) {
  const settings = { ...DEFAULT_DECISION, ...options };
  const mu = Number(calibration?.mu ?? DEFAULT_CALIBRATION.priorMu);
  const sigma = Math.max(
    Number(calibration?.sigma ?? DEFAULT_CALIBRATION.priorSigma),
    Number.EPSILON,
  );
  const threshold = Number(
    calibration?.threshold ?? mu - DEFAULT_CALIBRATION.strictness * sigma,
  );

  const deficit = Math.max(
    0,
    Math.min(1, (settings.shortUtteranceMs - Math.max(0, durationMs)) / settings.shortUtteranceMs),
  );
  const relaxation = deficit * settings.shortUtteranceBoostSigmas;
  const effectiveThreshold = Math.max(
    settings.minAbsoluteScore,
    threshold - relaxation * sigma,
  );
  const z = (score - mu) / sigma;
  const accepted = score >= effectiveThreshold;

  let reason = accepted ? "match" : "below_threshold";
  if (accepted && relaxation > 0 && score < threshold) reason = "match_short_utterance";
  if (!accepted && score >= threshold) reason = "below_absolute_floor";

  return {
    accepted,
    score: Number(score.toFixed(6)),
    z: Number(z.toFixed(4)),
    threshold: Number(threshold.toFixed(6)),
    effectiveThreshold: Number(effectiveThreshold.toFixed(6)),
    relaxation: Number(relaxation.toFixed(4)),
    shortUtteranceRelaxed: relaxation > 0,
    durationMs: Number(durationMs.toFixed(1)),
    reason,
  };
}
