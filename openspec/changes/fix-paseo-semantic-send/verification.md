# Verification and delivery

The operator reports 1.2.17 inserts the draft, ends with `Inserted — send manually`,
and tapping Paseo Send submits immediately. That identifies a pre-confirmation
failure but cannot distinguish the old selector/exception/rejected-action branches.
The phone was not connected, inspected, installed, recorded or certified.

## Verified hypotheses

- Public Paseo v0.10.2's RN 0.81.5 host, with all application JS replaced by the
  synthetic composer fixture, exports `message-input-root` as an Android view ID
  and native ancestor. Exact Send Pressables belong to this owner. ACTION_SET_TEXT
  reaches JS state; ACTION_CLICK invokes one mock handler with the exact draft.
- Pinned 1.2.17 production methods/selector at 204901d send in the standard RN
  layout but reject a moved, functional primary: two baseline assertions pass.
  The repaired production methods send once in both layouts. This establishes
  that old geometry assumptions were brittle, not that the phone used that layout.
- A controlled JVM child snapshot containing stale `enabled=false` reproduces
  1.2.17 rejecting the control. Current code refreshes the owned subtree before
  deciding eligibility and sends once. This is a cache failure hypothesis test,
  not direct evidence of the phone's cache state.

## Checks

- 75/75 hermetic tests passed with speaker auto-download off: wav, vad,
  transcriber, android-state/dictation/keys/pill/paseo/paseo-latency/recording.
  Includes separate pinned 1.2.16 draft-cache and 1.2.17 readiness-cache regressions.
- 34/34 native Android cases passed on the disposable API34 emulator: exact
  labels, short/multiline, IME states, delayed echo, disabled/ambiguous/global
  controls, manual edit/clear/remount, rejected CLICK/touch fallback, accepted
  ineffective activation without retry, confirmation limits and real kernel
  single/double Down with unchanged volume streams. No network/microphone permission.
- 18/18 RN cases passed through the checked-in repeatable runner: four primary
  labels × original/moved layout × short/multiline, plus disabled/ambiguous
  Pressables. Successful cases assert exact draft and exactly one callback.
  The test host has INTERNET, RECORD_AUDIO and CAMERA removed. Both test
  packages are uninstalled and accessibility settings restored by the runner.
- All five production VoiceVaultKeyService class files match the release build
  byte for byte in both native and RN fixtures. OpenSpec strict validation,
  shell syntax check and git diff whitespace check passed.

RN testing uses the released native host with synthetic JS, not full Paseo JS,
a daemon/server submission or physical phone. No real messages, audio, profiles,
private stores, model downloads or backend/runtime changes were used. The JVM
IPC-cost model is not measured phone latency.

Evidence retained locally in
`/home/qqp/.local/state/voice-vault-repair-20261001/semantic-send-verification-20261002/`:

| Receipt | SHA256 |
| --- | --- |
| hermetic.log | 9a7a89db90aaf5f8aa572bb4459a5f6cb03ffa2c617705f08e330442c8f7c5cb |
| native.log | f29f7e7fc2431bbbe18bbe286b745323a00e7f0deec58bfb524d996802acf433 |
| rn-baseline.log | 9b8d55b324583fcc91a1f23bb5e4502cc5732f944ab41341f952b43a24274c76 |
| rn-current.log | b2ab4f727847bf03ceac08116c51a0425e6e0313a0f2cfd0c55ee8ff0b0343c8 |

## Release

1.2.18 / code 22 built in isolation with the unchanged tracked keystore.
Certificate SHA256:
`24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0`.
APK: 215,038 bytes, SHA256
`a28c2f6f8eb1ebd186ac3ac640c03fd7ba47d9cfb2c7e74856ed52bb4aa8de73`.
The old 1.2.17 APK and new APK are retained in the existing local repair state.
The published file was checked against the previously observed hash before
atomic replacement. HTTPS download was verified byte for byte:
https://qq-box.tail580136.ts.net:3443/voice-vault.apk?v=22.
No server restart or device debugging is needed for the download.
