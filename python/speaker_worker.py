#!/usr/bin/env python3
"""Voice Vault speaker-embedding worker (persistent JSON-lines sidecar).

Loads an ONNX speaker-embedding model once through ``onnxruntime`` and answers
embedding requests on stdin/stdout, so per-segment gating costs a few
milliseconds instead of paying interpreter + model load on every request.

Protocol (one JSON object per line, stdin -> stdout):

    {"id": 1, "cmd": "info"}
        -> {"id": 1, "ok": true, "dim": 512, "sampleRate": 16000, ...}

    {"id": 2, "cmd": "embed", "clip": "<base64 int16 LE PCM>", "sampleRate": 16000}
        -> {"id": 2, "ok": true, "embedding": [...512 floats...], "ms": 8.4}

    {"id": 3, "cmd": "embed_batch", "clips": ["<b64>", "<b64>"], "sampleRate": 16000}
        -> {"id": 3, "ok": true, "embeddings": [[...], [...]], "ms": 15.1}

    {"id": 4, "cmd": "ping"} / {"id": 5, "cmd": "shutdown"}

On startup the worker prints a single ``{"event": "ready", ...}`` line. Errors
are reported per request as ``{"id": N, "ok": false, "error": "..."}`` and never
terminate the process.

Embeddings are always L2-normalised. Feature extraction uses
``kaldi_native_fbank`` when available (the reference implementation behind
sherpa-onnx) and otherwise ``fbank_numpy.compute_fbank``, which is validated
against the reference to < 1e-3 in log-mel space.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import sys
import time
import traceback
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fbank_numpy import compute_fbank  # noqa: E402

SAMPLE_RATE = 16000


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)


def emit(payload: dict) -> None:
    sys.stdout.write(json.dumps(payload, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def decode_clip(item: dict) -> np.ndarray:
    """Decode one clip to float32 samples in [-1, 1]."""
    if "wav" in item and item["wav"]:
        raw = base64.b64decode(item["wav"])
        with wave.open(io.BytesIO(raw)) as handle:
            if handle.getsampwidth() != 2:
                raise ValueError("only 16-bit PCM WAV is supported")
            data = handle.readframes(handle.getnframes())
            channels = handle.getnchannels()
        samples = np.frombuffer(data, dtype="<i2").astype(np.float32)
        if channels > 1:
            samples = samples.reshape(-1, channels).mean(axis=1)
    else:
        raw = base64.b64decode(item["clip"])
        samples = np.frombuffer(raw, dtype="<i2").astype(np.float32)
    return samples / 32768.0


def l2_normalize(vector: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(vector))
    if not np.isfinite(norm) or norm <= 0.0:
        return vector.astype(np.float32)
    return (vector / norm).astype(np.float32)


class SpeakerModel:
    def __init__(self, model_path: str, provider: str, threads: int):
        import onnxruntime as ort

        # onnxruntime-gpu 1.21+ can load the pip-installed CUDA/cuDNN wheels
        # from site-packages; harmless on CPU-only installs.
        try:
            ort.preload_dlls()
        except Exception:  # pragma: no cover - depends on the runtime build
            pass

        self.model_path = model_path
        available = ort.get_available_providers()
        requested = {
            "cpu": "CPUExecutionProvider",
            "cuda": "CUDAExecutionProvider",
            "tensorrt": "TensorrtExecutionProvider",
            "coreml": "CoreMLExecutionProvider",
        }.get(provider, provider)
        providers = []
        if requested in available:
            providers.append(requested)
        providers.append("CPUExecutionProvider")

        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, threads)
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        started = time.perf_counter()
        self.session = ort.InferenceSession(
            model_path, sess_options=options, providers=providers
        )
        self.load_ms = (time.perf_counter() - started) * 1000.0
        self.provider = self.session.get_providers()[0]
        self.input_name = self.session.get_inputs()[0].name

        metadata = self.session.get_modelmeta().custom_metadata_map
        self.metadata = dict(metadata)
        self.dim = int(metadata.get("output_dim") or self.session.get_outputs()[0].shape[-1])
        self.sample_rate = int(metadata.get("sample_rate") or SAMPLE_RATE)
        self.feature_normalize_type = metadata.get("feature_normalize_type", "")
        self.normalize_samples = metadata.get("normalize_samples", "1") not in ("0", "false", "False")
        self.framework = metadata.get("framework", "")
        self.comment = metadata.get("comment", "")
        self.last_timing = {"fbankMs": 0.0, "inferMs": 0.0}

        try:
            import kaldi_native_fbank  # noqa: F401

            self.fbank_backend = "kaldi_native_fbank"
        except ImportError:
            self.fbank_backend = "numpy"

    def features(self, samples: np.ndarray, sample_rate: int) -> np.ndarray:
        if sample_rate != self.sample_rate:
            raise ValueError(
                f"expected {self.sample_rate} Hz audio, received {sample_rate} Hz"
            )
        if self.fbank_backend == "kaldi_native_fbank":
            import kaldi_native_fbank as knf

            options = knf.FbankOptions()
            options.frame_opts.samp_freq = self.sample_rate
            options.frame_opts.frame_length_ms = 25
            options.frame_opts.frame_shift_ms = 10
            options.frame_opts.dither = 0
            options.frame_opts.snip_edges = False
            options.frame_opts.remove_dc_offset = True
            options.frame_opts.preemph_coeff = 0.97
            options.frame_opts.window_type = "povey"
            options.frame_opts.round_to_power_of_two = True
            options.mel_opts.num_bins = 80
            options.mel_opts.low_freq = 20
            options.mel_opts.high_freq = -400
            options.mel_opts.is_librosa = False
            options.use_energy = False
            options.use_log_fbank = True
            options.use_power = True
            extractor = knf.OnlineFbank(options)
            extractor.accept_waveform(self.sample_rate, samples)
            extractor.input_finished()
            frames = np.stack(
                [extractor.get_frame(i) for i in range(extractor.num_frames_ready)]
            ).astype(np.float32)
        else:
            frames = compute_fbank(
                samples,
                sample_rate=self.sample_rate,
                num_bins=80,
                window_type="povey",
                snip_edges=False,
            )

        if frames.shape[0] == 0:
            raise ValueError("clip is too short to extract a single frame")
        if self.feature_normalize_type == "global-mean":
            frames = frames - frames.mean(axis=0, keepdims=True)
        return frames.astype(np.float32)

    def embed(self, samples: np.ndarray, sample_rate: int) -> np.ndarray:
        started = time.perf_counter()
        frames = self.features(samples, sample_rate)
        fbank_ms = (time.perf_counter() - started) * 1000.0
        infer_started = time.perf_counter()
        outputs = self.session.run(None, {self.input_name: frames[None, :, :]})
        self.last_timing = {
            "fbankMs": round(fbank_ms, 3),
            "inferMs": round((time.perf_counter() - infer_started) * 1000.0, 3),
        }
        embedding = np.asarray(outputs[0], dtype=np.float32).reshape(-1)
        if embedding.shape[0] != self.dim:
            self.dim = int(embedding.shape[0])
        return l2_normalize(embedding)

    def info(self) -> dict:
        return {
            "dim": self.dim,
            "sampleRate": self.sample_rate,
            "provider": self.provider,
            "fbank": self.fbank_backend,
            "framework": self.framework,
            "featureNormalizeType": self.feature_normalize_type,
            "normalizeSamples": self.normalize_samples,
            "model": Path(self.model_path).name,
            "loadMs": round(self.load_ms, 2),
        }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="path to the ONNX model")
    parser.add_argument("--provider", default="cpu", help="cpu | cuda | tensorrt")
    parser.add_argument("--threads", type=int, default=2, help="intra-op threads")
    parser.add_argument("--warmup", action="store_true", help="run a warmup embedding")
    args = parser.parse_args()

    try:
        model = SpeakerModel(args.model, args.provider, args.threads)
    except Exception as error:  # pragma: no cover - startup failure path
        emit({"event": "error", "error": f"{error}", "traceback": traceback.format_exc()})
        return 2

    if args.warmup:
        try:
            model.embed(np.zeros(SAMPLE_RATE, dtype=np.float32), SAMPLE_RATE)
        except Exception as error:  # pragma: no cover
            log(f"warmup failed: {error}")

    emit({"event": "ready", **model.info()})

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        request_id = None
        try:
            request = json.loads(line)
            request_id = request.get("id")
            command = request.get("cmd", "embed")

            if command == "shutdown":
                emit({"id": request_id, "ok": True, "bye": True})
                return 0
            if command == "ping":
                emit({"id": request_id, "ok": True, "pong": True})
                continue
            if command == "info":
                emit({"id": request_id, "ok": True, **model.info()})
                continue

            sample_rate = int(request.get("sampleRate") or model.sample_rate)
            started = time.perf_counter()
            if command == "embed":
                embedding = model.embed(decode_clip(request), sample_rate)
                emit(
                    {
                        "id": request_id,
                        "ok": True,
                        "embedding": [float(v) for v in embedding],
                        "dim": int(embedding.shape[0]),
                        "ms": round((time.perf_counter() - started) * 1000.0, 3),
                        **model.last_timing,
                        "backend": f"onnxruntime:{model.provider}",
                    }
                )
            elif command == "embed_batch":
                clips = request.get("clips") or []
                embeddings = []
                timings = []
                for clip in clips:
                    embeddings.append(model.embed(decode_clip({"clip": clip}), sample_rate).tolist())
                    timings.append(dict(model.last_timing))
                emit(
                    {
                        "id": request_id,
                        "ok": True,
                        "embeddings": embeddings,
                        "timings": timings,
                        "dim": model.dim,
                        "ms": round((time.perf_counter() - started) * 1000.0, 3),
                        "backend": f"onnxruntime:{model.provider}",
                    }
                )
            else:
                emit({"id": request_id, "ok": False, "error": f"unknown command {command!r}"})
        except Exception as error:  # keep the worker alive for the next request
            emit(
                {
                    "id": request_id,
                    "ok": False,
                    "error": f"{type(error).__name__}: {error}",
                }
            )
            log(traceback.format_exc())

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
