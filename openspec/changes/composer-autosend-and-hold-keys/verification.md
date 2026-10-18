# Composer auto-send and hold keys verification

Source validation on 2026-10-17:

- `VOICE_VAULT_SPEAKER_AUTODOWNLOAD=off node --test test/wav.test.mjs test/vad.test.mjs test/transcriber.test.mjs test/android-*.test.mjs test/server.test.mjs test/recording-recovery-server.test.mjs`: **114 passed, 0 failed, 0 skipped**. Synthetic state only; no model downloads, recordings or profiles.
- Production composer methods run in `PaseoServiceHarness`:
  - appending to a draft, an empty composer, and a whitespace-normalized web echo
  - `Queue message` reporting `Queued ✓`
  - a nested web toolbar with no text search, and busy T3 labels never clicked
  - another focused input cancels
  - all prior Paseo regressions
- Production key methods run in `VolumeKeyHarness`:
  - taps on release, the 500 ms hold threshold, holds with key repeats acting once
  - mode-off Down pass-through and the Up-tap volume raise
  - mid-press mode changes, and overlapping keys
- Mutation checks: disabling the ancestor widening and disabling append each fail the harness.
- `openspec validate composer-autosend-and-hold-keys --strict`: passed.

Delivery and activation on 2026-10-17:

- [PR #28](https://github.com/hypermemetic-ai/voice-vault/pull/28) was squash-merged as `f71dc996298b52aa3b8719d126f27552111c5180`, including the signed APK.
- `apksigner verify --print-certs` shows the unchanged SHA-256 certificate `24:AF:79:AF:96:7D:37:80:6A:AC:2B:8D:82:2B:43:81:56:37:64:F0:76:D9:B3:7C:1E:EF:F6:F1:D8:14:1D:F0`. `aapt2 dump badging` reports versionName **1.4.0**, versionCode **26**. The keystore is unchanged.
- The primary checkout was fast-forwarded to `f71dc99`, preserving its untracked baseline material. No backend code changed, so `voice-vault.service` was not restarted.
- APK downloads through `http://127.0.0.1:3005` and `https://qq-box.tail580136.ts.net:3443` returned HTTP **200**, **231,422 bytes**, SHA-256 `694fda89ad1bf289a631135879fd8b8c771776bdaab28e458f5c2ca84bf6aed2`.

Operator-owned acceptance after installing 1.4.0:
- In T3 Code, dictate into a composer that already has text: confirm the text is appended and sent (`Sent ✓`).
- While an agent is running, confirm the message is queued (`Queued ✓`).
- Confirm the pill's `AUTO-SEND` / `NO SEND` tag follows a Down hold, then try Up taps and holds with the mode on and off.

No phone, microphone or real inference was operated here.
