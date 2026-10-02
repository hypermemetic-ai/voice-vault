# Offline native Paseo composer verification

This test-only APK runs **the production VoiceVaultKeyService**, including its
private PaseoTree adapter, semantic selector, echo gate, identity checks, event handling,
retry scheduling, native SET_TEXT/CLICK and rejected-action gesture fallback, and volume-key filter. No production
package exception, instrumentation hook, or service test branch was added.

The target is `sh.paseo.debug`, already recognized by production. The fixture APK
has **no INTERNET or microphone permission**, no recording service declaration,
and a mock submit callback that only counts clicks, saves the submitted mock
string, and remounts the EditText. It is never copied into release/public output.
The runner refuses physical-device serials and refuses to replace an existing
Paseo package. It restores accessibility settings and uninstalls its fixture.

## Fidelity and limits

The UI models pinned Paseo revision
`5599f9e567128a1240b3b15afab28bceef9d36a5`:

- `composer/input/input.tsx`: COLUMN inputWrapper, full-width input, 12dp gap,
  lower toolbar, 28dp controls, -6dp row horizontal margin, primary at right END.
- Named `message-input-root` native composer owner; no relative layout selection.
- Exact primary labels: Send message, Queue message, Send and interrupt,
  Send and steer. Context meter and voice are non-submit controls.
- `components/ui/text-input/text-input.native.tsx` and `composer/submit.ts`:
  clear/reset can remount the input, destroying saved accessibility identity.

These are real Android EditText/TextView/LinearLayout views and native input
method windows, **not React Native/Paseo runtime or server E2E**. Static short
and multiline input heights model the source geometry; the RN bridge,
TooltipTrigger/Pressable implementation, actual server queue/steer/interrupt
behavior, and the user's physical phone are not tested. The pinned source checkout
has no installed app dependencies/native build; its existing mobile script sends
real messages and was deliberately not run. The ticket permits this isolated
native fixture instead.

Paseo's named root is exported by React Native as a view ID and preserves the
native composer ancestor. This Java fixture models that identifier with an
accessibility delegate. Layout-only wrappers may flatten inside it without
changing ownership. A global exact Send control remains outside the owner.
The separate [React Native fixture](../react-native-paseo/README.md) tests the
real RN bridge, named-root export and semantic click with synthetic mock submission.

## Run

Use a booted, isolated API34 emulator with root adb access for offline kernel
key events (`adb -s emulator-5580 root`), with KVM access if necessary. Do not install this fixture on a user's device or a Paseo-bearing
emulator.

```sh
VV_EMULATOR_SERIAL=emulator-5580 \
VV_NATIVE_OUT=/tmp/vv-native-paseo \
bash test/native-paseo/run.sh
```

The runner builds with the same SDK/Java tools as the release build and caps
instrumentation at 240 seconds. It records `instrumentation.log` (native bounds,
labels, visibility, actions, chosen node, writes, ACTION_CLICK count, mock callback
count and feedback) and `logcat.log` under `VV_NATIVE_OUT`. `--build-only` builds
without requiring or changing an emulator; that is **not native verification**.

Historical evidence and limits of the prior 25-case run remain in
[VERIFICATION.md](VERIFICATION.md). The 1.2.17 verification receipt is tracked in
`openspec/changes/fix-paseo-dispatch-and-key-filter/verification.md`.

The current test-only harness covers 34 cases: four exact labels × short/multiline × actual
keyboard closed/open; delayed echo replacing an existing draft; disabled,
ambiguous and context-only controls; auto-send off; manual touchscreen send/remount,
clear, edit and navigation/remount; rejected CLICK bypassed by touch, ineffective touch and accepted ineffective CLICK without retries; literal
placeholder text; echo timeout; idle and processing Down shortcuts; and double-Down
ON/OFF toggling. Real Down checks also assert all relevant volume streams stay unchanged.

Submission displays `Sending to Paseo…`, then `Submitted to Paseo` when the native
composer clears/resets. This is local UI confirmation, not server acceptance.
Unchanged text after an accepted tap produces `Send not confirmed — tap Send`;
there is no automatic second dispatch. Manual cancellation shows `Auto-send canceled`.
Unresolved exact-echoed autosend distinguishes unavailable composer controls,
disabled/ambiguous/unavailable Send and a failed readiness check. Rejected dispatch
has separate feedback. A write without echo reports insertion unconfirmed.

`hardware-keys.py` responds only to whitelisted volume-key requests from the offline
instrumentation, writing EV_KEY press/release events to the isolated emulator's
keyboard device. Software-injected UiAutomation keys bypass the native filter and
are not suitable evidence of hardware key consumption. This helper requires root
adb on the disposable emulator and refuses physical serials.

The 1.2.16 source repair uses a two-second readiness deadline, accepts a unique
exact local send control without optional toolbar peers, and revalidates normal
composer resizing. The fixture waits/auto-send-off feedback have been updated
accordingly. Those changes were covered by JVM production-method regressions;
the historical native run above does not validate this newer production version.

The 1.2.18 semantic repair prefers one ACTION_CLICK. Only a rejected action may
fall back to a gesture at the live control; an accepted ineffective action never
triggers another activation. Current evidence is in
`openspec/changes/fix-paseo-semantic-send/verification.md`. The 1.2.16 paragraph
above and VERIFICATION.md describe historical behavior, not the current selector.
