# Tasks

## 1. Server recording durability

- [ ] 1.1 Add backward-compatible recording metadata migration and atomic intake/processing/failure transitions in `src/db.mjs` and `src/server.mjs`; add temporary-store tests proving a stub transcription exception or injected ENOSPC leaves the same indexed original accessible through history/audio, and run `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off node --test test/recording-recovery-server.test.mjs`.
- [ ] 1.2 Handle intake and metadata persistence failures honestly, classify processing errors without exposing private details, and reconcile ownerless processing rows on startup; verify injected raw-write failure, database failure, temporary-audio ENOSPC and restart scenarios in the same hermetic server suite.
- [ ] 1.3 Add optional stable upload identity, content-conflict detection, recording lookup and explicit retry routes with atomic attempt claims; verify lost-response replay, concurrent requests, completed-outcome replay, original retention and unchanged legacy raw/multipart success fields in the server recovery suite.
- [ ] 1.4 Extend deletion to safely remove owned server copies and report incomplete cleanup; document identity/status/retry compatibility in README's API section, and verify only selected synthetic files/rows are removed, malformed identifiers cannot escape storage and documented requests match route tests.

## 2. Android durable recording state

- [ ] 2.1 Add a versioned atomic recording index with stable UUID, capture metadata, audio association, safe failure state and attempt generation; add production state/storage harness tests for atomic-write failure, prior-version recovery and restart transitions, and run `node --test test/android-recovery.test.mjs`.
- [ ] 2.2 Integrate capture intent and finalized-recording persistence into `VoiceVaultService` before upload; verify successful finalization, failed recorder stop, local storage failure and upload failure in the recovery harness and existing `test/android-recording.test.mjs` without microphone use.
- [ ] 2.3 Reconcile existing dictation files in both app recording locations, exclude unrelated/active files, and protect unresolved audio/metadata from normal retention; verify legacy upgrade, repeated scan, missing/invalid audio, successful pruning and more than 20 audio files or 50 transcripts in the synthetic recovery suite.

## 3. Android History and audio retrieval

- [ ] 3.1 Merge local recording entries, legacy cached text and remote history by known identity without dropping failures or changing original capture order; verify blank transcript failures, limited remote pages, same-entry completion and unchanged newest-text preview in recovery/history harness tests.
- [ ] 3.2 Render unresolved History cards with status/reason, available audio actions and disabled impossible actions; add bounded local playback and user-initiated export using narrowly granted owned audio, and verify generated action wiring, unavailable-audio behavior, export URI ownership and all-source Android compilation against the existing SDK without APK packaging.
- [ ] 3.3 Implement explicit deletion with local suppression of stale history rediscovery and honest partial server cleanup; verify selected-item-only deletion, concurrent-retry protection, remote outage and stale refresh in the recovery harness, and document recovery/export/deletion behavior beside existing History features in README.

## 4. Manual transcription retry

- [ ] 4.1 Add a service-owned recovery action accepting a stored UUID, reuse bounded upload handling, and resolve ambiguous outcomes through server lookup before retry/re-upload; verify HTTP failure, response loss, compatibility fallback, same-record update and original audio retention with synthetic loopback requests.
- [ ] 4.2 Guard recovery ownership against repeated taps, active dictation, Activity recreation, cancellation, restart and late results; verify no duplicate attempt, no original deletion and no clipboard/insertion/auto-send side effect in production-method/state harness tests.
- [ ] 4.3 Wire Retry transcription and explicit Copy into History, including valid no-speech/speaker-rejected outcomes; verify failure-to-success stays on one item with original capture time, repeated failure stays retryable, and existing dictation copy/insertion behavior remains covered by the recording/state suites.

## 5. Integration and authorized implementation delivery

- [ ] 5.1 Run the dedicated recovery suites plus `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-state.test.mjs test/android-recording.test.mjs`, compile all Android sources to temporary classes, run strict OpenSpec validation and `git diff --check`, and record actual results plus device limits in change verification notes without real audio/profile inputs or model downloads.
- [ ] 5.2 In the later apply assignment, commit and push only the implementation's scoped files, follow the existing PR/merge path, and verify remote delivery with `node /home/qqp/projects/qq-workflows/scripts/git-status.mjs`; preserve unrelated untracked baseline material and linked worktrees.
- [ ] 5.3 Carry through authorized ordinary activation after source checks: coordinate around active server requests, preserve rollback artifacts and recordings, activate additive server support, build/publish the APK with the unchanged signing identity and verify its certificate and served bytes. Report operator-owned device installation/playback/export/retry acceptance separately; perform no device operation or real inference unless explicitly included in the implementation assignment.
