# Verification — 1.2.19

The user reports insertion without sending and suggests a delayed Stop-to-Send
transition. Two synthetic reproductions fail under pinned 1.2.18 (`88b3217`)
and pass with the repair: absence of the application composer ID, and a three-second
Stop-to-Send delay. This identifies real implementation weaknesses, not the
exact cause on the operator's phone.

## Checks

- Hermetic Node suite: 77/77, synthetic/temporary state, mock transcription,
  `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off`.
- JVM production-method regressions cover cached draft/readiness, stale Stop,
  generic focused input, delayed native echo, event and polling transitions,
  cancellation, bounded timeout and no minimum wait. A ready control dispatches
  before 100ms in the controlled IPC model; this is not a device latency benchmark.
- Real React Native 0.81.5 host, with all application JavaScript replaced by a
  synthetic composer and INTERNET/RECORD_AUDIO/CAMERA removed: 20/20 cases.
  No composer view ID, arbitrary input label, four submit semantics, moved and
  multiline controls, same circular control Stop-to-Send after three seconds,
  disabled and ambiguous controls. Exactly one mock callback per successful
  draft; no Stop activations. Delayed case completes within 4.5 seconds including
  instrumentation polling and its final 200ms check.
- The same RN fixture with 1.2.18 inserts but reports
  `Inserted — composer controls unavailable`; zero submit callbacks.
- Real Android native service: 34/34 cases, including an unrelated global Send,
  keyboard open/closed, exact delayed echo, manual edit/clear/remount cancellation,
  rejected-action touch fallback and no retry of accepted ineffective activation.
  Kernel hardware Down events verify submission, processing completion, double
  Down ON/OFF and unchanged volume streams. Emulator-only, no recording/network.
- All five VoiceVaultKeyService and five PaseoSelection class files are byte-for-byte
  identical between the release build and both tested native/RN service builds.
- Stock OpenSpec strict validation and Git whitespace check pass.

## Release

Version 1.2.19 / code 23, 215038 bytes. Original signing certificate preserved:
`24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0`.
APK SHA-256:
`d45968db0827d38a3a1bcd3f28c6b57392e5fe2f3c532a643ffe55e55bfda4af`.

Prior 1.2.18 retained. New APK atomically published via the existing static route,
without a server restart. HTTPS download exactly matches the signed artifact:
https://qq-box.tail580136.ts.net:3443/voice-vault.apk?v=23.

Local logs, class comparison, signing receipt and both release APKs retained at
`/home/qqp/.local/state/voice-vault-repair-20261001/screen-readiness-20261002/`.

## Limits

No physical phone, real audio, real chats, full Paseo JavaScript, server acceptance
or live inference tested. Successful mock submission does not prove the user's
phone is repaired. The implementation still requires an accessible submit action
and distinguishable native input context; missing or ambiguous semantics produce
manual-send feedback. User installation and a Volume Down check on their phone
are the remaining real-device validation.
