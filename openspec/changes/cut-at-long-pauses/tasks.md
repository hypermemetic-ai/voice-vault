# Tasks

## 1. Cutter

- [x] 1.1 Add the long-pause rule (design D1, D2) and `VOICE_VAULT_PIECE_LONG_PAUSE_MS` to `src/chunker.mjs`
- [x] 1.2 Add synthetic tests to `test/chunker.test.mjs`: the 18 s / 9 s / 14 s shape cuts at 22.5 s; a 1.5 s pause, leading and trailing silence do not cut; the cut is emitted when speech resumes; batch and four push sizes agree; `longPauseMs: 0` disables; env override is read

## 2. Transcriber

- [x] 2.1 Take the single-request path only when the audio is one piece (design D3); test that a 41 s recording with a 9 s pause makes two requests
- [x] 2.2 Document the rule and variable in README

## 3. Verification

- [x] 3.1 Hermetic suite passes with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`
- [x] 3.2 Re-transcribing recording 731ae919 locally yields the missing statement
- [ ] 3.3 After merge, restart `voice-vault.service` on qq-box
