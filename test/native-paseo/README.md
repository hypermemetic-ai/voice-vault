# Offline native Paseo composer verification

This test-only APK runs **the production VoiceVaultKeyService**, including its
private PaseoTree adapter, selector, echo gate, identity checks, event handling,
retry scheduling and actual accessibility SET_TEXT/CLICK actions. No production
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

Android's accessibility export actually flattens the non-important COLUMN and
row groups in this fixture. That exposed a second selector requirement: local
same-parent input/control plus an adjacent toolbar peer when groups are omitted.
The JVM regressions additionally cover the unflattened source hierarchy.

## Run

Use a booted, isolated API34 emulator, with KVM access granted by the administrator
if necessary. Do not install this fixture on a user's device or a Paseo-bearing
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

Completed evidence is the prior 25-case run; the operator waived the expanded
final rerun after KVM permissions were lost. See [VERIFICATION.md](VERIFICATION.md)
for exact results, production-bytecode matching, APK hash and limits. Do not claim
that the expanded harness below ran.

The current test-only harness covers 28 cases: four exact labels × short/multiline × actual
keyboard closed/open; delayed echo replacing an existing draft; disabled,
ambiguous and context-only controls; auto-send off; manual touchscreen send/remount,
clear, edit and navigation/remount; rejected CLICK without retries; literal
placeholder text; and echo timeout with unconfirmed-insertion feedback.

Successful automatic dispatch deliberately does not announce server acceptance.
Manual cancellation preserves a sentinel feedback rather than replacing it with
a misleading composer/draft failure. Unresolved exact-echoed autosend reports
`Inserted — send manually`; a write without echo reports insertion unconfirmed.

The 1.2.16 source repair uses a two-second readiness deadline, accepts a unique
exact local send control without optional toolbar peers, and revalidates normal
composer resizing. The fixture waits/auto-send-off feedback have been updated
accordingly. Those changes were covered by JVM production-method regressions;
the historical native run above does not validate this newer production version.
