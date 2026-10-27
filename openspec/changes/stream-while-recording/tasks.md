# Tasks

## 1. Server

- [x] 1.1 Add `pieceCache` to `transcribeAudioFile`'s piece loop with the D4 key; tests in `test/transcriber.test.mjs`: cache hits skip the stub, a cache entry with different PCM is ignored, the single-piece path ignores the cache
- [x] 1.2 Add `src/stream-session.mjs` (session file, append semantics D2, incremental cutter and serial background piece transcription D3, idle check D6) with an injectable `transcribe`; unit tests with synthetic PCM in `test/stream-session.test.mjs`
- [x] 1.3 Factor the `/api/transcribe` intake into a shared function and add the append and finish routes (D2, D5, D7) plus session sweep (D6) in `src/server.mjs`; tests in `test/stream-server.test.mjs` covering every spec scenario with a stub transcriber and temp dirs
- [x] 1.4 Document the two routes in README.md

## 2. Android

- [x] 2.1 Replace `MediaRecorder` with `AudioRecord` writing a canonical WAV in `VoiceVaultService` (D1, D8), keeping pause/resume, cancel, max-duration and error behaviour
- [x] 2.2 Add the streamer (D9) and the stop sequence with finish and fallback (D10), reusing `DictationUpload`'s connection setup; WAV uploads use `audio/wav`, existing M4A files keep `audio/mp4`
- [x] 2.3 Bump to 1.5.0 / versionCode 29 (D11); update the static Android tests in `test/android-*.test.mjs` that assert the old recorder, and add static checks for AudioRecord, the stream routes and the fallback; `bash android/build-apk.sh` succeeds

## 3. Integration

- [x] 3.1 Hermetic test set passes with `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`
- [ ] 3.2 Activation: restart `voice-vault.service`, install the APK over the existing app with `adb install -r`, record more than 2 minutes on the phone, confirm one history entry, stream appends and finish in the server log, and the stop-to-result time
