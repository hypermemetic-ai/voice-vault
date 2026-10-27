# Design

## Context

See proposal.md (Why). Current pipeline in `src/transcriber.mjs`
`transcribeAudioFile`: ffmpeg → 16 kHz mono WAV → whole-file VAD trim
(`prepareAudioForTranscription`) → optional low-frequency rescue → optional
speaker gate (concatenates accepted segments) → one `defaultTranscribe` call
(daemon socket, then direct `handy` dGPU/iGPU/CPU) → `cleanTranscript`.

Facts that shape the approach:

- The model's GGUF metadata shows `att_context_left/right = -1` (full attention)
  and `stt.capability.streaming = False`. libtranscribe's streaming decoder needs
  an RNN-T head, and this model is TDT. `handy --serve-transcription` only
  accepts whole WAV paths. So pieces must be cut outside the engine.
- Measured warm-daemon ASR time: about 0.3 s floor plus about 1 s per minute of
  audio. The per-request floor makes very small pieces wasteful.
- The failed allocations (9.31 GB for 532 s) fit the quadratic attention term.
  Scaled down, a 45 s piece needs on the order of 100–300 MB of compute buffer
  on top of 0.7 GB of weights.

## Goals / Non-Goals

**Goals:**
- Any recording length succeeds on Bark within about 1–1.5 GB of GPU memory.
- Short recordings (one piece) go through exactly the current code path.
- A cutter that a future send-while-recording session can feed incrementally.

**Non-Goals:**
- Sending audio while recording, parallel piece transcription, or any client
  or API change.
- Changing handy/transcribe-cpp, the speaker gate, the VAD trim, or the
  low-frequency rescue.
- Re-transcribing existing recordings.
- Tuning piece size for latency. That waits for the short-clip overhead
  research and stays configurable.

## Decisions

### D1. Cut after the gate, inside `transcribeAudioFile`
Pieces are cut from the exact PCM that would have been sent today: trimmed,
rescued or gated. The gate and VAD stay untouched, and every backend tier
benefits. *Alternative:* cutting in the daemon. Rejected: the direct tiers
would still get whole files, and the daemon would need to merge text.

### D2. New `src/chunker.mjs`: an incremental, deterministic cutter
`createPieceCutter({ sampleRate, maxMs = 45000, windowStartMs = 20000,
windowEndMs = 40000, overlapMs = 1500, minPauseMs = 200 })` exposes
`push(int16)` → finalized pieces and `finish()` → remaining pieces. A batch
helper `cutPieces(pcm, opts)` is `push(all)` + `finish()`.

A piece is `{ startSample, endSample, forced }`. Overlap is expressed by the
next piece's `startSample` being earlier than the previous `endSample`.

Cut rule for the piece starting at `s`:
- A cut is decided only once audio beyond `s + maxMs` exists, or at `finish()`.
  If the remainder at `finish()` is ≤ `maxMs`, it is the last piece. Because
  the decision waits until the remainder is known to exceed `maxMs`, the
  incremental and batch results are identical (spec: Incremental cutting).
- Inside `[s + windowStartMs, s + windowEndMs]`, compute 10 ms frame levels
  with `frameLevelsDb` from `src/vad.mjs` and slide a `minPauseMs` window to
  find the stretch with the lowest mean level. It counts as a pause when its
  level is below the speech threshold derived from `s … s + windowEndMs`. That
  is the same rule as `segmentSpeech`: the 20th-percentile noise floor + 9 dB,
  with a −50 dB absolute floor. Cut at the centre of that stretch.
- With no pause, cut at `s + maxMs`, mark the piece `forced`, and start the
  next piece at `cut − overlapMs`.

The threshold is computed only from audio inside the decision span, so the
result never depends on how the audio was split into pushes.

*Alternatives:* fixed 30 s cuts with overlap everywhere. Rejected: words get
split, and every seam needs a fuzzy text merge. Using `segmentSpeech` segment
boundaries directly. Rejected: it takes a global noise floor over the whole
input, so it cannot run incrementally with identical results.

### D3. Single-piece fast path
If the speech PCM is ≤ `maxMs`, the existing single `transcribe(filteredWavPath)`
call runs unchanged. Response fields are identical to today, plus
`pieces: 1`.

### D4. Sequential per-piece cascade
Each piece is written as its own temp WAV in the existing temp dir and passed
to `transcribe(pieceWav, \`${reqId}-p${i}\`)`. The cascade (`defaultTranscribe`)
runs per piece, so one piece falling back does not redo the others. Pieces run
strictly in order: the GPU is shared and the daemon queue is serial anyway. If
any piece exhausts every tier, the whole transcription fails with the existing
terminal-failure shape plus the failing piece index. No partial text is
returned as success.

### D5. Joining
Texts are trimmed and joined with single spaces. Forced seams only get a
dedup: compare the last ≤ 12 words of the earlier text with the first words of
the later one, case-insensitive and punctuation-stripped. Drop the longest
exact repeated run (≥ 1 word) from the later text. Pause seams are never
deduplicated. `cleanTranscript` (silence phrases, echo loops, normalizer) runs
once on the joined text.

### D6. Outcome reporting
Keep `text`, `durationMs`, `audioSentMs`, `transcribeMs` (sum of pieces),
`backend` and `backendAttempts`. `backend` is the most degraded tier used by
any piece (daemon < dGPU < iGPU < CPU), so a fallback is never hidden.
`backendAttempts` concatenates attempts tagged with the piece index. Add
`pieces` (count) and `pieceBackends` (array). The server stores the outcome
JSON as it does today, and API consumers see only added fields.

### D7. Daemon length guard
`src/whisper-daemon.mjs` reads the WAV header (RIFF chunks, `data` size,
sample rate, channels, bits) before enqueueing. If the duration exceeds
`WHISPER_MAX_AUDIO_MS` (default 120000) it answers HTTP 413
`{ ok: false, error: "audio exceeds 120 s bound" }`, and handy is never
invoked. An unreadable header is passed through as today.

### D8. Same model on every tier
`queryHandyDirect` uses `--model ${WHISPER_MODEL || "turbo"}` (the env var the
daemon already uses). The `turbo` default keeps behaviour on hosts without
configuration. On qq-box, `voice-vault.service` gets a drop-in setting
`WHISPER_MODEL=bark-3.0-clean-Q8_0`.

### D9. Configuration
Env overrides read at call time: `VOICE_VAULT_PIECE_MAX_MS`,
`VOICE_VAULT_PIECE_WINDOW_START_MS`, `VOICE_VAULT_PIECE_WINDOW_END_MS`,
`VOICE_VAULT_PIECE_OVERLAP_MS`. Invalid combinations (for example a window
past the max) fall back to the defaults.

## Risks / Trade-offs

- [Per-request floor of about 0.3 s per piece adds about 0.6 s per minute for
  long recordings] → Acceptable now. Sizes are configurable and get retuned
  after the overhead research. The send-while-recording follow-up hides it.
- [Less acoustic context per piece could slightly change accuracy at seams] →
  Cuts sit in pauses, and Parakeet is trained on utterances well under 45 s.
- [A forced seam whose duplicated words the model renders differently on each
  side is not deduplicated] → Rare in dictation (breathing pauses). At worst a
  word or two is repeated, never lost.
- [GPU estimate is extrapolated, not measured] → Activation measures the
  `handy` peak with `nvidia-smi` while a synthetic 10-minute WAV runs.
- [Sequential pieces keep long recordings at about 1–1.3 s per minute] → Known.
  This change targets reliability; speed comes from the follow-up.

## Migration Plan

1. Merge. Restart `voice-vault.service` (picks up code and the new
   `WHISPER_MODEL` drop-in) and `voice-vault-asr.service` (length guard). Each
   restart is a blip of a few seconds.
2. Verify: hermetic tests pass. A synthetic 10-minute WAV, passed to
   `transcribeAudioFile` directly against the live daemon socket (so it creates
   no history entry), completes on `daemon_gpu` with `pieces > 1`. The `handy` peak in
   `nvidia-smi` stays ≤ 1.5 GB.
3. Rollback: revert the merge, remove the drop-in, restart both services.
