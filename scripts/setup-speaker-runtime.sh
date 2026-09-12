#!/usr/bin/env bash
# Create the Python runtime used by the ONNX speaker-embedding sidecar.
#
#   scripts/setup-speaker-runtime.sh            # CPU onnxruntime (recommended)
#   scripts/setup-speaker-runtime.sh --gpu      # CUDA build + pip CUDA libs
#
# The venv lives outside the repository (~/.cache/voice-vault) and is picked up
# automatically by src/speaker.mjs (or point VOICE_VAULT_PYTHON at it).
set -euo pipefail

MODE="cpu"
for arg in "$@"; do
    case "$arg" in
        --gpu) MODE="gpu" ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${VOICE_VAULT_CACHE:-$HOME/.cache/voice-vault}"
if [[ "$MODE" == "gpu" ]]; then
    VENV="$CACHE_DIR/venv-gpu"
else
    VENV="$CACHE_DIR/venv"
fi

PYTHON_BIN=""
for candidate in python3.12 python3.11 python3.10 python3; do
    if command -v "$candidate" >/dev/null 2>&1; then
        PYTHON_BIN="$(command -v "$candidate")"
        break
    fi
done
if [[ -z "$PYTHON_BIN" ]]; then
    echo "Error: no python3 interpreter found." >&2
    exit 1
fi

echo "==> Using $PYTHON_BIN ($($PYTHON_BIN --version 2>&1))"
echo "==> Creating virtualenv at $VENV"
mkdir -p "$CACHE_DIR"
"$PYTHON_BIN" -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip

echo "==> Installing numpy, kaldi-native-fbank and onnxruntime ($MODE)"
if [[ "$MODE" == "gpu" ]]; then
    "$VENV/bin/pip" install --quiet onnxruntime-gpu numpy kaldi-native-fbank \
        nvidia-cublas-cu12 nvidia-cuda-runtime-cu12 nvidia-cufft-cu12 \
        nvidia-curand-cu12 nvidia-cudnn-cu12 nvidia-nvjitlink-cu12
else
    "$VENV/bin/pip" install --quiet onnxruntime numpy kaldi-native-fbank
fi

echo "==> Verifying the sidecar imports and model load"
MODEL="${VOICE_VAULT_SPEAKER_MODEL:-$ROOT/models/3dspeaker_campplus_en_voxceleb_16k.onnx}"
if [[ ! -f "$MODEL" ]]; then
    echo "    (model not present yet - run: npm run speaker:fetch-model)"
fi
"$VENV/bin/python" - <<'PY'
import numpy, onnxruntime
print(f"    onnxruntime {onnxruntime.__version__} · providers {onnxruntime.get_available_providers()}")
try:
    import kaldi_native_fbank
    print("    kaldi-native-fbank available (reference fbank backend)")
except ImportError:
    print("    kaldi-native-fbank missing (numpy fbank fallback will be used)")
PY

echo
echo "Done. Voice Vault finds this interpreter automatically:"
echo "    $VENV/bin/python"
echo "Optionally export VOICE_VAULT_PYTHON=$VENV/bin/python"
