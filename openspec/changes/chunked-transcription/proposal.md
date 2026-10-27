# Proposal

## Why

The Bark 3.0 Clean model (a Parakeet-TDT 0.6B v3 fine-tune with full attention)
encodes a whole recording as one graph, so its GPU compute buffer grows roughly
with the square of the clip length. On the shared 6 GB RTX A2000, clips past
about 6–8 minutes fail to allocate and the `handy` process aborts. Recordings
past about 15 minutes also exceed the 15 s daemon bound. Either way the server
silently drops to `handy --transcribe-file --model turbo` (Whisper), which
produced visibly shorter text for some 16-minute recordings. Eight recordings
from 2026-10-05 to 2026-10-27 (9–29 min) took that path. A successful long clip
also leaves the oversized buffer reserved: 5.7 GB stayed held after a 6-minute
clip until the daemon was restarted.

## What Changes

- Transcription splits speech audio longer than one chunk into pieces of about
  30 s, cut at the quietest pause between 20 and 40 s and never longer than
  45 s. Pieces are transcribed one at a time and joined into one transcript.
  Short recordings remain a single request, exactly as today.
- When no pause exists (continuous speech), a forced cut overlaps the next
  piece by about 1.5 s. The duplicated words at the seam are removed from the
  text.
- The splitter is a standalone module that can accept audio incrementally, so
  a later send-while-recording mode can reuse it unchanged.
- Each piece runs through the existing backend cascade on its own. A failed
  piece falls back without redoing the pieces that already succeeded.
- The warm ASR daemon refuses WAV input longer than a configured bound
  (default 120 s) with an explicit error instead of letting the model process
  crash.
- The direct `handy` fallback tiers use the same model as the daemon
  (`WHISPER_MODEL`, Bark in production) instead of a hard-coded `turbo`.
- Old recordings are not re-transcribed.

## Capabilities

### New Capabilities
- `long-recording-transcription`: transcription of recordings of any length in
  bounded, pause-aligned pieces whose results join into one transcript.

### Modified Capabilities
- `bounded-transcription-and-insertion`: the bounded cascade now applies per
  piece, the daemon bounds the audio length it accepts, and every tier uses the
  configured model.

## Impact

- Code: `src/transcriber.mjs` (piece loop and join), a new `src/chunker.mjs`,
  `src/whisper-daemon.mjs` (length guard), tests in `test/` (synthetic audio and
  stubbed backends only).
- Runtime: `voice-vault.service` gets `WHISPER_MODEL=bark-3.0-clean-Q8_0` so the
  direct tiers match the daemon. Expected peak `handy` GPU memory falls from up
  to the full 6 GB to about 1–1.5 GB.
- API: unchanged. `/api/transcribe` and the recording endpoints keep their
  request and response shape. The outcome gains piece metadata (count and
  per-piece backend) alongside the existing fields.
- Latency: unchanged for recordings up to one piece. Long recordings take about
  1–1.3 s per minute of audio after upload. The send-while-recording follow-up
  is what removes that wait.
