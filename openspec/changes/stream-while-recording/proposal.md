# Proposal

## Why

Transcription starts only after the phone stops recording and uploads the
file, so the wait after a long dictation grows with its length (about 1.5 s
per minute of speech: 22 s for 14 minutes). Short dictations already return in
about 0.5 s. The model must see bounded pieces anyway (chunked transcription),
so pieces that are finished while the user is still talking can be
transcribed before they stop.

## What Changes

- The Android app records 16 kHz mono 16-bit PCM with `AudioRecord` (same
  `VOICE_RECOGNITION` source; on the Pixel 10 it uses the identical capture
  path as today's `MediaRecorder`) into a local WAV file, which stays the
  durable local copy.
- While recording, the app appends new PCM to a server stream session for the
  recording every few seconds. Streaming is best effort and never affects
  recording.
- The server cuts the streamed audio with the existing incremental piece cutter
  and transcribes each finished piece in the background.
- At stop, the app sends the remaining audio and asks the server to finish.
  The server checks the streamed audio against the app's length and hash,
  saves it as the recording, and runs the normal pipeline, which reuses the
  already-transcribed pieces. Only the last piece is left to transcribe.
- When finishing is not possible (no session, mismatch, network error), the
  app uploads the whole WAV through the existing upload path, with the same
  recording identity, and all existing retry and recovery behaviour applies.
- One history entry per dictation, unchanged insertion behaviour, unchanged
  response shape.

## Capabilities

### New Capabilities
- `streamed-recording-intake`: audio streamed during recording, verified and
  turned into a recording at stop, with transcription of finished pieces done
  ahead of time.

## Impact

- Server: `src/server.mjs` (stream and finish routes, intake refactor, session
  sweep), new `src/stream-session.mjs`, `src/transcriber.mjs` (piece cache).
- Android: `VoiceVaultService` (AudioRecord + WAV + streamer), upload path for
  WAV, version 1.5.0 (versionCode 29). Same signing key.
- Network: the local WAV is about 1.9 MB per minute (today's AAC is about
  0.7 MB). Streaming spreads it over the recording; the fallback upload sends
  it whole.
- Latency: a dictation of any length should finish about as fast as a 45 s one
  (one tail piece), around 1–2 s after stop.
