# Tasks

## 1. Whole recording

- [x] 1.1 In `src/transcriber.mjs` `transcribeAudioFile`, once speech is detected pass the full converted WAV (or the full high-passed copy when the rescue found the speech) to the gate, piece cutter and model, and report its length in `durationMs`/`audioSentMs` (design D1); keep the no-speech return unchanged
- [x] 1.2 Add tests to `test/transcriber.test.mjs`: silence 4 s + speech-like 3 s + silence 4 s → the stub receives ~11 s; silent input → no speech and no stub call. Verify they pass with the existing `test/lf-rescue.test.mjs` and `test/gate.test.mjs`

## 2. Integration checks

- [x] 2.1 Run the full hermetic set used for chunked-transcription and fast-audio-intake with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`; all pass
- [x] 2.2 Activation after merge: restart `voice-vault.service`; run one noisy past recording (chosen locally, never copied out) through `transcribeAudioFile` locally (no server, no history entry) and confirm the word count is close to the whole-recording count (about 250), printing counts only
