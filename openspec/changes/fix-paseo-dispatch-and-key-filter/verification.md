# Verification — Android 1.2.17

## Reproduced failure and repair
The first offline API34 native run reproduced one successful text write followed by cancellation before any Send action. A refreshed saved editor had the new draft while Android child enumeration still supplied the previous cached text; observing that cached text after exact echo canceled the gate. The production path now uses the refreshed native editor for ownership, text and geometry. A pinned production-method regression fails against 1.2.16 (49a24848) and passes against this repair.

Down now returns from the hardware-key filter before tree queries. Its 220 ms single/double distinction restores auto-send toggling; consumed key releases survive mode changes. Ordinary scroll/system-window events revalidate ownership. Submission uses one freshly validated native tap; rejected gesture dispatch alone permits one ACTION_CLICK fallback. Accepted dispatch is never retried. Confirmation waits for native composer reset, including transient remount/keyboard changes, and reports unconfirmed outcomes explicitly.

## Checks
- 74/74 hermetic audio, backend and Android tests pass with speaker auto-download off. Synthetic data and stub backends only.
- 33/33 offline native cases pass on an isolated API34 emulator. The fixture has no INTERNET/microphone permission and counts mock submissions only. Includes four exact labels × short/multiline × keyboard closed/open, delayed echo, negative selection, manual edit/clear/remount, one ineffective tap, real kernel Down press/release, both double-Down toggles and stub processing completion.
- Native Down preserves music, ring, notification, alarm and system stream volumes. Software-injected UiAutomation keys bypassed the hardware filter, so final key evidence uses real EV_KEY events on the disposable emulator keyboard.
- Production key-service class and all four nested classes match the native tested bytecode exactly.
- Strict OpenSpec validation and whitespace checks pass.

Native log: `/tmp/vv-paseo-1217-native-10/instrumentation.log`; SHA-256 `4c5b8435349a0c38ef9375aaa6ad21e7825aa15ec900061a4f3120878b72ff3b`. The initial failing/cancellation traces remain in `/tmp/vv-paseo-1217-native-3/`.

## Release receipt
Version 1.2.17, code 21, 215038 bytes.
APK SHA-256: `11104b6af10a2371c1a6f658b0f3d372165a94bc5b3c1beb77d136ed62e64712`.
Signing certificate SHA-256: `24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0` (original identity).
Isolated stock build and atomic public-file replacement completed. HTTP 200 download from `https://qq-box.tail580136.ts.net:3443/voice-vault.apk?v=21` matches the signed artifact byte-for-byte. Previous 1.2.16 and new 1.2.17 APKs are retained under `/home/qqp/.local/state/voice-vault-repair-20261001/`.

## Limits and ownership
Native views/keyboard/taps and the production service were exercised; actual React Native/Paseo runtime, server acceptance and the operator's physical phone were not. No real recordings, transcripts, profiles, real inference, chat messages, backend restarts or phone operation occurred. The operator installs the served update for the remaining physical-phone check. Repair commit `b3030fb49faf3d451be7040716f0a82ac79bfbd2` was pushed and merged through [PR #20](https://github.com/hypermemetic-ai/voice-vault/pull/20), merge `66e0190`. The final checklist receipt follows the same PR path; unrelated readiness files/worktrees remain untouched.
