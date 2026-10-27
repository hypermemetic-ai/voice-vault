# Tasks

## 1. Staged intake and lookup

- [x] 1.1 Add the `stagingDir` option (default `VOICE_VAULT_STAGING_DIR`, unset = disabled) to `createAppServer` and route the intake write protocol to it with the archive fallback on any staging write error (design D1, D2); add server tests: staging configured → stub transcriber receives a staged path; not configured → archive path; staging writes throwing ENOSPC → archive path and HTTP 200. Verify they pass
- [x] 1.2 Replace `ownedPath` with the two-location resolver and make delete and the cleanup check cover both folders (design D3); add tests: `GET /api/audio/:id` serves identical bytes while staged and after archive; `DELETE` leaves no copy in either folder; the duplicate-upload replay still works for a staged recording. Verify they pass

## 2. Archive and sweep

- [x] 2.1 Add the serial archive queue run after each attempt's response and after intake failures, with the live-attempt skip, copy/fsync/rename/fsync, re-check and unlink, and the identical-destination case (design D4), plus a test hook `server.archiveIdle()`; add tests: success → audio only in archive with identical bytes; transcription failure → archived, then `POST /api/recording/:id/transcribe` succeeds from the archive. Verify they pass
- [x] 2.2 Add the startup and periodic sweep (design D5) with `VOICE_VAULT_STAGING_SWEEP_MS`, abandoned `.part` cleanup after 1 h, orphan files left alone, and timer cleanup on server close; add tests: a leftover staged file with a matching row is archived at start; a move that fails once leaves the staged copy retrievable and a later sweep completes it; an orphan file is left in place. Verify they pass
- [x] 2.3 Document `VOICE_VAULT_STAGING_DIR` and `VOICE_VAULT_STAGING_SWEEP_MS` in the README environment table and describe the staging → archive flow in the storage section; verify both names appear in the README

## 3. Integration checks

- [x] 3.1 Run `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off node --test test/server.test.mjs test/recording-recovery-server.test.mjs test/transcribe-e2e.test.mjs` plus the hermetic readiness set (`test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs`); all pass with synthetic data only (any pre-existing environment failure must fail identically on the base commit)
- [ ] 3.2 Activation on qq-box after merge per design Migration Plan: create the staging folder, add the `staging.conf` drop-in, restart `voice-vault.service`, run one synthetic upload under an `activation-check-*` id, confirm staging empties and the archive holds the file, then delete the synthetic recording via the API
