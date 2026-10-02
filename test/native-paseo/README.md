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

The synthetic UI uses real Android EditText/TextView/LinearLayout controls,
short and multiline drafts, a lower toolbar and native keyboard windows. The
composer exports no app-specific view ID. A tempting exact Send control outside
the input group must remain excluded. Primary labels are Send message, Queue
message, Send and interrupt, and Send and steer; context and voice controls are
not submit actions.

This exercises Android accessibility, native actions and hardware keys. It does
not run React Native, full Paseo JavaScript or server delivery, and does not
verify the user's phone. The separate [React Native fixture](../react-native-paseo/README.md)
tests the RN bridge, generic input ownership and delayed Stop-to-Send transition
using synthetic text and a mock handler.

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

Current readiness checks refresh the focused draft and live submit control.
Insertion echo has a two-second bound; Send readiness has a separate five-second
bound starting at the first exact echo. There is no minimum wait: a ready control
sends immediately. Events advance the pending check without extending its budget.
Historical verification receipts remain under `openspec/changes/`.
