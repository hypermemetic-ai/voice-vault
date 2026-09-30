# Paseo correction verification evidence

## Delivered production version

Only `PaseoSelection.java` and `VoiceVaultKeyService.java` change production
behavior. Other-app insertion/send, 220ms key timing, idle display-only pill and
server code are unchanged. The release manifest/identity/signing key are unchanged.
No fixture/instrumentation classes occur in the release DEX.

The native-tested version is the delivered version. After the 25-case run, a dead
state cleanup and exception-feedback refinement were reverted per assignment
revision 7, rather than shipping a changed production version without its native
rerun. Recompiling production into the test harness produced **byte-for-byte
identical Java class files** to the 25-case run, including all nested classes.
All nine relevant class files from the final release build also match that native
run exactly:

| Class | SHA-256 (tested and final, identical) |
| --- | --- |
| PaseoSelection | `00a7d99553de518738affa33b5134bf34e1f730e998e4dc5ad93f5d403223369` |
| PaseoSelection.Gate | `e9a144a6267f7271c8a01fd1a00928c35a809d02d43a202d100d72ee1e1ce6b5` |
| VoiceVaultKeyService | `5c95b6219d808e96614792dd7366eba003a8d9b99eda1c9ad6a738a77227f00f` |
| VoiceVaultKeyService.PaseoTree | `7105e94bbee2cc45b81263737a75245fed98e887c68c788e456d828f8be98328` |

## Completed verification

- Focused regressions:
  `node --test test/android-paseo.test.mjs test/android-keys.test.mjs test/android-pill.test.mjs test/android-dictation.test.mjs test/android-state.test.mjs`
  — **33/33 passed**, final wall time **1.09s**. Includes source-faithful COLUMN
  and flattened native-tree cases, short/multiline and keyboard layout positions,
  exact labels, hint normalization, existing-draft replacement, delayed echo,
  uniqueness/disabled controls, irreversible cancellation, dispatch gating and
  feedback distinctions; retained daily-use key/pill/state checks.
- Native command:
  `VV_EMULATOR_SERIAL=emulator-5580 VV_NATIVE_OUT=/tmp/vv-paseo-evidence/native-run-4 bash test/native-paseo/run.sh`
  — **25 cases passed**, wall time **63.66s**, on Android **14 / API34**, x86_64
  `test_avd`, emulator **37.1.11.0**, KVM acceleration, 320×640 / 160dpi.
  Actual production bound accessibility service, adapter and native SET_TEXT/CLICK
  actions; offline Android views, **not actual Paseo/RN/server/phone E2E**.
  Keyboard-open bounds genuinely moved, rather than being fabricated test positions.
  Successful writes had one mock submit callback, never repeated after 1300ms.
  Manual completion case invoked the native mock view's `performClick` callback;
  it did not test a physical touch on a user's phone.
- Native cases: 16 label × short/multiline × keyboard closed/open combinations;
  delayed echo/existing draft; disabled, ambiguous and context-only controls;
  auto-send off; manual send/remount, clear, edit and navigation/remount.
  Raw exported bounds/labels/actions and counts: [`evidence/native-25.log`](evidence/native-25.log).
- Release: `bash android/build-apk.sh` — succeeded, final wall time **5.46s**.
  `apksigner verify --print-certs public/voice-vault.apk` — passed in **0.12s**,
  original certificate SHA-256
  `24af79af967d37806aac2b8d822b4381563764f076d9b37c1eeff6f1d8141df0`.
  Package `ai.hypermemetic.voicevault`, versionCode 18 / versionName 1.2.14,
  minSdk26 / targetSdk34.
- Final APK SHA-256:
  **`ddbdab7bb90ea2d9fc7b6fec9725c1a7e20654c4624b73ff3057f1e30eaf9ca7`**.
  Copies: `android/build/VoiceVault.apk`, `public/voice-vault.apk` in this worktree.
- `git diff --check` passed. Default checkout remained clean, changes uncommitted;
  no independent review, managed landing/default sync or live publication performed
  by the implementer. No server restart, external message or phone install.

## Expanded test limitation and host troubleshooting

The current test-only harness adds three cases (rejected CLICK without retry,
literal placeholder text, unconfirmed echo), and uses injected touchscreen input
for its manual-send case. **Those expanded 28 native cases were NOT run**. They
compile, but final execution was blocked when the `/dev/kvm` node lost its
administrator-granted qqp ACL. Revision 7 explicitly waived that expanded rerun
and requested delivery of the already-native-tested production version. JVM gate
regressions still cover the associated policies. Do not present the archived
25-case native log as an execution of the expanded harness.

Initial default-acceleration startup failed for KVM permissions. A bounded
`-accel off` software attempt did not boot in 240s; one additional software attempt
was stopped when administrator access became available. No worker ACL/group/sudo
changes were made. The accelerated emulator booted in 26.8s and completed the
25-case run; its 600s safety cap subsequently stopped it. A later startup failed
because the KVM ACL had disappeared. No more boot or permission attempts were
made after revision 7. No owned emulator remains running.

Full transient diagnostics/builds remain at `/tmp/vv-paseo-evidence/`, including
native-run-4/logcat.log, failed boot logs, final focused/release/signature logs and
native-production-match/classes. Native fixtures were uninstalled and emulator
accessibility settings restored by the runner.

## Managed-publication handoff

Publish the supplied signed `public/voice-vault.apk` through managed delivery,
without rebuilding a different artifact or restarting the server. Suggested
cache-busted URL (publication pending implementer handoff):

`https://qq-box.tail580136.ts.net:3443/voice-vault.apk?v=ddbdab7bb90e`

Verify the served bytes against the full hash above after managed synchronization.
Physical-phone compatibility remains unverified; no claim is made about this
user's exact event timing, actual Paseo RN hierarchy or server acceptance.
