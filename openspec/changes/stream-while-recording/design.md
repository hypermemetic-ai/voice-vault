# Design

## Context

- Server intake: `POST /api/transcribe` (raw body or multipart, `X-Recording-Id`,
  `X-Duration-Ms`, `X-Device`), fingerprint = SHA-256 of the uploaded bytes,
  staging then archive, `processRecording` → `transcribeAudioFile`.
- `transcribeAudioFile`: ffmpeg → 16 kHz mono WAV, whole-file speech check,
  whole recording sent (keep-whole-recording), pieces from
  `src/chunker.mjs` `cutPieces` when longer than 45 s, sequential per-piece
  `transcribe`, forced-seam dedup, join, `cleanTranscript`.
- `createPieceCutter` is incremental and gives identical boundaries to
  `cutPieces` on the same samples.
- Android: `VoiceVaultService` records with `MediaRecorder` (MPEG-4/AAC,
  16 kHz) into the app's files dir; `DictationUpload` posts raw bytes with
  `Content-Type: audio/mp4` and `X-Recording-Id`; `RecordingStore`/
  `RecordingIndex`/`RecoveryTranscription` keep and retry local recordings.

## Decisions

### D1. Wire format: canonical WAV
Audio is 16 kHz, mono, signed 16-bit little-endian PCM. The recording's bytes
are a canonical 44-byte WAV header followed by the PCM: `RIFF`, size
`36 + N`, `WAVE`, `fmt ` chunk of 16 bytes (format 1, 1 channel, 16000 Hz,
byte rate 32000, block align 2, 16 bits), `data`, size `N`. This is exactly
what `encodePcm16Wav` in `src/wav.mjs` produces. Phone and server build it the
same way, so the fingerprint (SHA-256 of the WAV bytes) is identical whether the
server assembles it from the stream or the phone uploads the file.

### D2. Append endpoint
`POST /api/recording/:id/stream?offset=<bytes>` with an
`application/octet-stream` body of PCM bytes (no header), at most 4 MB per
request. `offset` is the PCM byte offset of the body's first byte.
- `offset == received`: append, fsync not required (the phone keeps the
  durable copy), answer `200 { ok: true, received }`.
- `offset + length <= received`: already have it, answer 200 with `received`.
- `offset < received < offset + length`: append only the new tail, answer 200.
- `offset > received`: answer `409 { ok: false, errorCategory: "stream_gap", received }`.
- Offsets must stay even; a recording that already exists as a saved
  recording answers `409 { errorCategory: "already_saved" }`.
- Total session size is bounded by the existing upload limit (`MAX_UPLOAD`).

The session lives in the staging folder (archive folder when staging is not
configured) as `<id>.stream.pcm`, plus in-memory state.

### D3. Ahead-of-time pieces
Each session owns a `createPieceCutter` (same options as `transcribeAudioFile`,
via `resolvePieceOptions`). Every append pushes the new samples. Each finalized
piece is written to a temp WAV and transcribed with `defaultTranscribe`
(id `${id}-s${index}`), strictly one at a time per session. Results go in a
piece cache keyed by `${startSample}:${endSample}:${sha1(piece PCM)}`
holding `{ text, backend, transcribe_ms, attempts }`. A failed piece is simply
not cached. Nothing is written to history during streaming.

### D4. Piece cache in `transcribeAudioFile`
New option `pieceCache` (a `Map` or object with `get(key)`). In the piece loop,
before calling `transcribe`, compute the same key; on a hit use the cached
result as the piece's response. The single-piece fast path does not consult
the cache. Because the key includes the PCM hash, a cache built from different
audio (rescue high-pass, speaker gate) can never be used by mistake.
`processRecording` passes the session's cache when one exists for the id, then
waits for that session's in-flight piece (if any) before transcribing so the
cache is as full as possible.

### D5. Finish endpoint
`POST /api/recording/:id/stream/finish` with JSON
`{ "bytes": N, "sha256": "<hex of the canonical WAV>", "durationMs": D, "device": "..." }`.
The server checks that the session holds exactly `N` PCM bytes and that the
SHA-256 of the canonical WAV built from them equals `sha256`. On success it
runs the same intake as `POST /api/transcribe` with those WAV bytes
(extension `.wav`, the given duration and device), then `processRecording`,
and answers with exactly the `/api/transcribe` response. The intake logic is
factored into one shared function so both routes behave identically
(identity checks, replay of an existing recording, staging, archive).
- No session: `404 { errorCategory: "no_stream" }`.
- Length or hash mismatch: `409 { errorCategory: "stream_mismatch", received }`.
After a successful finish (or a mismatch) the session file and state are removed.

### D6. Session lifetime
Sessions idle for more than 1 hour are deleted by the existing staging sweep
(and at startup). The in-memory state is dropped with them. After a server
restart a session file without in-memory state is still valid for finish; it
just has no cached pieces.

### D7. Fallback upload also uses the cache
If `POST /api/transcribe` arrives for an id with a live session, the server
uses that session's cache too, then removes the session. This makes the
phone's fallback (finish failed because of a network error) still fast when
most audio was streamed.

### D8. Android recording
`VoiceVaultService` replaces `MediaRecorder` with `AudioRecord`
(`VOICE_RECOGNITION`, 16000 Hz, mono, PCM 16-bit, buffer at least
`getMinBufferSize` × 2). A capture thread writes PCM to
`dictation_<start>.wav` after a 44-byte placeholder header and patches the
header at stop (canonical, D1). Pause/resume, cancel, max-duration and error
handling keep their current behaviour. Enrollment (`VoiceEnrollActivity`) is
unchanged.

### D9. Android streaming
A streamer thread, started with the recording, every 3 s reads the bytes
written since the last acknowledged offset (up to 4 MB) and POSTs them to the
append endpoint with the same base URL, TLS and auth handling as
`DictationUpload`. On 409 `stream_gap` it continues from the server's
`received`. On any other error it backs off (3 s → max 30 s) and keeps
recording. It never blocks or stops the capture thread.

### D10. Android stop
At stop the WAV header is patched, the streamer sends the remaining bytes
(bounded wait, about 10 s), then calls finish with the PCM length, the WAV's
SHA-256, duration and device. A 200 answer is handled exactly like today's
upload response (insertion, history, pending dictation). Any failure falls back
to the existing upload path with the WAV file, `Content-Type: audio/wav` and
the same recording id, and the existing local store, retry and recovery logic
treat the WAV like today's M4A files. Old M4A files still pending on the phone
still upload as `audio/mp4`.

### D11. Version
`versionName` 1.5.0, `versionCode` 29. Signed with `android/release.keystore`
via `android/build-apk.sh` only.

## Risks / Trade-offs

- [Larger files (PCM)] → About 1.9 MB/min, spread over the recording; the
  fallback upload is larger than AAC. Acceptable for dictation lengths.
- [Pieces transcribed during recording compete with other requests on the
  daemon queue] → One piece per 45 s per active session; tiny load.
- [Network drops during recording] → Streaming resumes from the server's
  offset; at worst the fallback upload sends the whole file.
- [Phone and server WAV bytes differ] → Finish answers `stream_mismatch` and
  the phone falls back to the full upload, so a dictation is never lost.

## Migration Plan

Merge, restart `voice-vault.service` (server first: old app versions keep
using `/api/transcribe`), then build with `android/build-apk.sh` and install
over the existing app with `adb install -r` (same key; data kept). Verify on
the phone with a recording longer than 2 minutes: the history entry exists
once, server logs show stream appends and a finish, and the time from stop to
result is a few seconds. Rollback: reinstall the previous APK, revert the merge.
