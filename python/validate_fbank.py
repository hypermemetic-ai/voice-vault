#!/usr/bin/env python3
"""Validate python/fbank_numpy.py against kaldi_native_fbank (reference).

Usage:
    python3 python/validate_fbank.py [wav ...]

With no arguments a deterministic synthetic speech-like signal is used, so the
check runs without any external audio. Exits non-zero when the maximum absolute
difference exceeds --tolerance (default 2e-3 in log-mel space; the two
implementations differ only in float32 rounding order).
"""

from __future__ import annotations

import argparse
import sys
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from fbank_numpy import compute_fbank  # noqa: E402


def synthetic_speech(seconds: float = 3.0, sample_rate: int = 16000) -> np.ndarray:
    """Speech-like signal: pitch glide + formant-ish harmonics + noise bursts."""
    rng = np.random.default_rng(7)
    t = np.arange(int(seconds * sample_rate), dtype=np.float64) / sample_rate
    f0 = 120.0 + 30.0 * np.sin(2 * np.pi * 0.7 * t)
    phase = 2 * np.pi * np.cumsum(f0) / sample_rate
    signal = np.zeros_like(t)
    for harmonic, gain in enumerate([1.0, 0.5, 0.33, 0.25, 0.15, 0.1], start=1):
        signal += gain * np.sin(harmonic * phase)
    # Slow amplitude modulation so different frames have different energy.
    signal *= 0.4 + 0.6 * np.abs(np.sin(2 * np.pi * 1.3 * t))
    signal += 0.01 * rng.normal(size=t.shape)
    return (signal / np.max(np.abs(signal))).astype(np.float32)


def read_wav(path: str) -> tuple[int, np.ndarray]:
    with wave.open(path) as handle:
        sample_rate = handle.getframerate()
        assert handle.getsampwidth() == 2, "expected 16-bit PCM"
        assert handle.getnchannels() == 1, "expected mono"
        data = np.frombuffer(handle.readframes(handle.getnframes()), dtype=np.int16)
    return sample_rate, data.astype(np.float32) / 32768.0


def reference_fbank(samples: np.ndarray, sample_rate: int = 16000) -> np.ndarray:
    import kaldi_native_fbank as knf

    opts = knf.FbankOptions()
    opts.frame_opts.samp_freq = sample_rate
    opts.frame_opts.frame_length_ms = 25
    opts.frame_opts.frame_shift_ms = 10
    opts.frame_opts.dither = 0
    opts.frame_opts.snip_edges = False
    opts.frame_opts.remove_dc_offset = True
    opts.frame_opts.preemph_coeff = 0.97
    opts.frame_opts.window_type = "povey"
    opts.frame_opts.round_to_power_of_two = True
    opts.mel_opts.num_bins = 80
    opts.mel_opts.low_freq = 20
    opts.mel_opts.high_freq = -400
    opts.mel_opts.is_librosa = False
    opts.use_energy = False
    opts.use_log_fbank = True
    opts.use_power = True

    extractor = knf.OnlineFbank(opts)
    extractor.accept_waveform(sample_rate, samples)
    extractor.input_finished()
    return np.stack(
        [extractor.get_frame(i) for i in range(extractor.num_frames_ready)]
    ).astype(np.float32)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", nargs="*")
    parser.add_argument("--tolerance", type=float, default=2e-3)
    args = parser.parse_args()

    try:
        import kaldi_native_fbank  # noqa: F401
    except ImportError:
        print("kaldi_native_fbank is not installed; skipping cross-validation.")
        return 0

    cases: list[tuple[str, int, np.ndarray]] = []
    if args.wav:
        for wav_path in args.wav:
            sample_rate, samples = read_wav(wav_path)
            cases.append((wav_path, sample_rate, samples))
    else:
        cases.append(("synthetic speech", 16000, synthetic_speech()))
        cases.append(("synthetic 0.35 s burst", 16000, synthetic_speech(0.35)))

    worst = 0.0
    for name, sample_rate, samples in cases:
        mine = compute_fbank(samples, sample_rate=sample_rate)
        reference = reference_fbank(samples, sample_rate=sample_rate)
        if mine.shape != reference.shape:
            print(f"FAIL {name}: shape {mine.shape} != reference {reference.shape}")
            return 1
        diff = np.abs(mine - reference)
        max_diff = float(diff.max())
        mean_diff = float(diff.mean())
        worst = max(worst, max_diff)
        print(
            f"{name}: frames={mine.shape[0]} max|delta|={max_diff:.3e} "
            f"mean|delta|={mean_diff:.3e}"
        )

    if worst > args.tolerance:
        print(f"FAIL: max difference {worst:.3e} > tolerance {args.tolerance:.3e}")
        return 1
    print(f"OK: numpy fbank matches kaldi_native_fbank (max delta {worst:.3e})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
