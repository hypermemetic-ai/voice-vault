"""Kaldi-compatible 80-dim log-mel filterbank, implemented with numpy only.

This mirrors the feature extraction that the sherpa-onnx / 3D-Speaker CAM++
speaker-embedding models were trained with:

    frame_length = 25 ms, frame_shift = 10 ms, snip_edges = false
    dither = 0, remove_dc_offset = true, preemph = 0.97
    window = povey, fft = next power of two >= frame_length
    mel bins = 80, low_freq = 20 Hz, high_freq = -400 (i.e. Nyquist - 400)
    use_power = true (|FFT|^2), use_log_fbank = true, use_energy = false

Numerical equivalence with ``kaldi_native_fbank`` (the reference C++
implementation used by sherpa-onnx) is covered by
``python/validate_fbank.py`` and by ``test/fbank.test.mjs``.
"""

from __future__ import annotations

import numpy as np

# float32 epsilon, matching kaldi's MelBanks energy floor before log().
FLOAT32_EPSILON = float(np.finfo(np.float32).eps)


def mel_scale(freq):
    """Kaldi's mel scale: 1127 * ln(1 + f / 700)."""
    return 1127.0 * np.log(1.0 + np.asarray(freq, dtype=np.float64) / 700.0)


def next_power_of_two(value: int) -> int:
    result = 1
    while result < value:
        result <<= 1
    return result


def mel_filterbank(
    num_bins: int = 80,
    sample_rate: int = 16000,
    fft_size: int = 512,
    low_freq: float = 20.0,
    high_freq: float = -400.0,
) -> np.ndarray:
    """Triangular mel filter weights, shape [num_bins, fft_size // 2].

    This is a direct port of ``MelBanks::MelBanks`` from kaldi-native-fbank:
    filters are *not* area-normalised (unlike librosa's slaney norm), which is
    what kaldi and the 3D-Speaker recipes use.
    """
    if high_freq <= 0:
        high_freq = sample_rate / 2.0 + high_freq

    num_fft_bins = fft_size // 2
    fft_bin_width = float(sample_rate) / float(fft_size)
    mel_low = float(mel_scale(low_freq))
    mel_high = float(mel_scale(high_freq))
    mel_delta = (mel_high - mel_low) / (num_bins + 1)

    centers = mel_low + mel_delta * (np.arange(num_bins, dtype=np.float64) + 1.0)
    left = centers - mel_delta
    right = centers + mel_delta

    bin_freqs = fft_bin_width * np.arange(num_fft_bins, dtype=np.float64)
    bin_mels = mel_scale(bin_freqs)[None, :]

    up = (bin_mels - left[:, None]) / (centers[:, None] - left[:, None])
    down = (right[:, None] - bin_mels) / (right[:, None] - centers[:, None])
    weights = np.where(bin_mels <= centers[:, None], up, down)
    weights[(bin_mels <= left[:, None]) | (bin_mels >= right[:, None])] = 0.0
    return weights.astype(np.float32)


def _frame_signal(
    samples: np.ndarray,
    frame_length: int,
    frame_shift: int,
    snip_edges: bool,
) -> np.ndarray:
    """Extract frames, reflecting at the edges exactly like kaldi.

    kaldi's reflection maps ``-1 -> 0``, ``-2 -> 1``, ``dim -> dim - 1`` and
    ``dim + 1 -> dim - 2`` (repeated reflections for pathological cases), which
    the modulo construction below reproduces.
    """
    num_samples = int(samples.shape[0])
    if num_samples == 0:
        return np.zeros((0, frame_length), dtype=np.float32)

    if snip_edges:
        if num_samples < frame_length:
            return np.zeros((0, frame_length), dtype=np.float32)
        num_frames = 1 + (num_samples - frame_length) // frame_shift
        starts = np.arange(num_frames, dtype=np.int64) * frame_shift
    else:
        num_frames = (num_samples + frame_shift // 2) // frame_shift
        starts = (
            np.arange(num_frames, dtype=np.int64) * frame_shift
            + frame_shift // 2
            - frame_length // 2
        )

    if num_frames <= 0:
        return np.zeros((0, frame_length), dtype=np.float32)

    indices = starts[:, None] + np.arange(frame_length, dtype=np.int64)[None, :]
    if num_samples == 1:
        indices = np.zeros_like(indices)
    else:
        period = 2 * num_samples
        indices = np.mod(indices, period)
        indices = np.where(indices >= num_samples, period - 1 - indices, indices)
    return samples[indices].astype(np.float32)


def _window_function(window_type: str, frame_length: int) -> np.ndarray:
    if window_type in ("povey", "hamming", "hanning", "sine", "blackman"):
        a = 2.0 * np.pi / (frame_length - 1)
    elif window_type == "hann":
        a = 2.0 * np.pi / frame_length
    elif window_type == "rectangular":
        return np.ones(frame_length, dtype=np.float32)
    else:
        raise ValueError(f"Unsupported window type: {window_type}")

    i = np.arange(frame_length, dtype=np.float64)
    if window_type == "hanning":
        w = 0.5 - 0.5 * np.cos(a * i)
    elif window_type == "sine":
        w = np.sin(0.5 * a * i)
    elif window_type == "hamming":
        w = 0.54 - 0.46 * np.cos(a * i)
    elif window_type == "hann":
        w = 0.50 - 0.50 * np.cos(a * i)
    elif window_type == "povey":
        w = np.power(0.5 - 0.5 * np.cos(a * i), 0.85)
    else:  # blackman
        w = 0.42 - 0.5 * np.cos(a * i) + 0.08 * np.cos(2.0 * a * i)
    return w.astype(np.float32)


def compute_fbank(
    samples: np.ndarray,
    sample_rate: int = 16000,
    num_bins: int = 80,
    frame_length_ms: float = 25.0,
    frame_shift_ms: float = 10.0,
    dither: float = 0.0,
    remove_dc_offset: bool = True,
    preemph_coeff: float = 0.97,
    window_type: str = "povey",
    snip_edges: bool = False,
    low_freq: float = 20.0,
    high_freq: float = -400.0,
    use_power: bool = True,
) -> np.ndarray:
    """Return log-mel features with shape [num_frames, num_bins]."""
    wave = np.asarray(samples, dtype=np.float32).reshape(-1)
    frame_length = int(round(sample_rate * frame_length_ms / 1000.0))
    frame_shift = int(round(sample_rate * frame_shift_ms / 1000.0))
    fft_size = next_power_of_two(frame_length)

    frames = _frame_signal(wave, frame_length, frame_shift, snip_edges)
    if frames.shape[0] == 0:
        return np.zeros((0, num_bins), dtype=np.float32)

    if dither != 0.0:
        rng = np.random.default_rng(0)
        frames = frames + rng.normal(0.0, dither, size=frames.shape).astype(np.float32)
    if remove_dc_offset:
        frames = frames - frames.mean(axis=1, keepdims=True)
    if preemph_coeff != 0.0:
        shifted = np.empty_like(frames)
        shifted[:, 1:] = frames[:, :-1]
        shifted[:, 0] = frames[:, 0]
        frames = frames - preemph_coeff * shifted

    frames = frames * _window_function(window_type, frame_length)[None, :]

    spectrum = np.fft.rfft(frames, n=fft_size, axis=1)
    if use_power:
        power = (spectrum.real**2 + spectrum.imag**2).astype(np.float32)
    else:
        power = np.abs(spectrum).astype(np.float32)

    filters = mel_filterbank(num_bins, sample_rate, fft_size, low_freq, high_freq)
    mel_energies = power[:, : filters.shape[1]] @ filters.T
    mel_energies = np.maximum(mel_energies, FLOAT32_EPSILON)
    return np.log(mel_energies).astype(np.float32)
