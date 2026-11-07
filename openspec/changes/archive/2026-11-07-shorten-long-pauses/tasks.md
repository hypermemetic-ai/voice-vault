# Tasks

## 1. Pause shortening

- [x] 1.1 Add `squeezePauses` and `VOICE_VAULT_PIECE_LONG_PAUSE_MS` to `src/chunker.mjs` (design D1)
- [x] 1.2 Apply it in the transcriber's single-request and piece paths and in the stream session's background pieces (design D2)
- [x] 1.3 Synthetic tests: a 9 s pause becomes 1 s with speech samples intact; a 1.9 s pause, -40 dBFS room tone and `longPauseMs: 0` leave audio untouched; the model receives 33 s for the 18 s / 9 s / 14 s shape; edge silences are shortened while room tone is kept
- [x] 1.4 Document the rule and variable in README

## 2. Verification

- [x] 2.1 Hermetic suite passes with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`
- [x] 2.2 Re-transcribing 731ae919 and f9a245d6 locally keeps the missing statement and phrases; across the 40 most recent recordings with long pauses, word counts move by -8 to +8 (mostly formatting such as "G two" -> "G2"; a few short phrases differ either way) except 731ae919 (+41)
- [x] 2.3 After merge, restart `voice-vault.service` on qq-box
