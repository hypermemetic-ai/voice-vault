# Tasks

## 1. Piece cutter

- [x] 1.1 Add `src/chunker.mjs` with `createPieceCutter(opts)` (`push`, `finish`) and `cutPieces(pcm, opts)` per design D2, including env-driven defaults per D9; verify with `node --check src/chunker.mjs`
- [x] 1.2 Add `test/chunker.test.mjs` using synthetic PCM only (noise bursts and silences): audio ≤ max → one piece; a 400 ms silence at 27 s → cut inside it; continuous sound → forced cut at 45 s with next start 1.5 s earlier; 16-minute input → every piece ≤ 45 s and pieces cover the input in order; one-call vs 37 irregular pushes + finish → identical boundaries; invalid env combination falls back to defaults. Verify `node --test test/chunker.test.mjs` passes

## 2. Piece transcription and join

- [x] 2.1 In `src/transcriber.mjs`, after the gate, take the single-piece fast path (unchanged call, adds `pieces: 1`) when speech PCM ≤ max; otherwise write each piece WAV to the temp dir and run `transcribe` sequentially per piece with id `${reqId}-p${i}` (design D3, D4); verify the existing `node --test test/transcriber.test.mjs test/gate.test.mjs test/lf-rescue.test.mjs` still pass
- [x] 2.2 Implement the join (design D5: forced-seam-only word dedup, `cleanTranscript` once) and outcome fields (design D6: `transcribeMs` sum, most-degraded `backend`, piece-tagged `backendAttempts`, `pieces`, `pieceBackends`), and terminal failure with the failing piece index
- [x] 2.3 Add tests to `test/transcriber.test.mjs` with a stub `transcribe` and synthetic WAV written to a temp dir: a long input yields pieces ≤ 45 s in order and the joined text "Alpha. Beta. Gamma." for three pieces; forced-seam dedup removes repeated words; a pause seam keeps a repeated word; a stub failing for piece 2 only on the daemon tier falls back for that piece alone (use `defaultTranscribe` with injected `socketQuery`/`handyQuery`); a piece failing every tier fails the whole transcription with the piece index. Verify they pass
- [x] 2.4 Pass `pieces` and `pieceBackends` through the server's response and stored outcome in `src/server.mjs`; verify with a test in `test/recording-recovery-server.test.mjs`

## 3. Backend guard and model

- [x] 3.1 In `src/whisper-daemon.mjs`, parse the WAV header before enqueueing and answer HTTP 413 with `{ ok: false, error }` when duration > `WHISPER_MAX_AUDIO_MS` (default 120000), passing unreadable headers through (design D7). Export or factor the header-duration helper so it is unit-testable without spawning handy; verify with a test in `test/transcriber.test.mjs` (or a new `test/whisper-daemon.test.mjs`) using a synthetic 10-minute header and a 30 s header
- [x] 3.2 Make `queryHandyDirect` pass `--model ${WHISPER_MODEL || "turbo"}` (design D8); add a test asserting the spawned args with `WHISPER_MODEL=bark-3.0-clean-Q8_0` via a stub `bin` script that echoes its argv as JSON; verify it passes
- [x] 3.3 Document `WHISPER_MODEL` (direct tiers), `WHISPER_MAX_AUDIO_MS` and the `VOICE_VAULT_PIECE_*` variables in the README environment table and describe long-recording behaviour in the transcription section, replacing the inaccurate "streaming sliding-window support for arbitrarily long recordings" claim; verify the README table lists all five names and the old claim is gone

## 4. Integration checks

- [x] 4.1 Run the hermetic readiness set plus the new and touched tests with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`: `node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs test/transcript-normalize.test.mjs test/chunker.test.mjs test/gate.test.mjs test/lf-rescue.test.mjs`; all pass with no model downloads and no network
- [x] 4.2 Activation on qq-box after merge: add `~/.config/systemd/user/voice-vault.service.d/asr-model.conf` with `Environment=WHISPER_MODEL=bark-3.0-clean-Q8_0`, daemon-reload, restart `voice-vault-asr.service` and `voice-vault.service`; run a synthetic 10-minute WAV through `transcribeAudioFile` directly (no server, no history entry) and confirm `backend: daemon_gpu`, `pieces > 1`, and the `handy` process peak in `nvidia-smi` ≤ 1.5 GB
