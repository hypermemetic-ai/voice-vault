# React Native composer integration test

This optional test runs the unmodified production VoiceVaultKeyService against
React Native 0.81.5 TextInput/Pressable controls in the native host from the public
Paseo v0.10.2 APK. It replaces **all** Paseo JavaScript with composer.js: an
in-memory mock handler, synthetic text, and layout/disabled/duplicate controls.
It removes INTERNET, RECORD_AUDIO and CAMERA from the test host manifest and
refuses a physical serial or an emulator already containing either Paseo package.
Both test packages are uninstalled and accessibility settings restored on exit.
The test host APK must never be published or installed on a phone.

The stable `message-input-root` testID, text input label, four primary labels and
mobile Pressable match Paseo's composer/input/input.tsx and tooltip.tsx at
5599f9e567128a1240b3b15afab28bceef9d36a5. The actual RN bridge, conditional mounting
of Send after SET_TEXT, native accessibility export, semantic ACTION_CLICK and
mock submit callback run on Android. This is **not the full Paseo JavaScript,
a daemon/server delivery test, or verification of the operator's phone**.

Use a clean, disposable, booted API34 emulator. Prepare optional libraries and
host **outside this repository**; no project dependency, Gradle/native RN build,
model download, backend, login, microphone, or real message is required:

```sh
VV_RN_WORK=/dev/shm/vv-rn-test
mkdir -p "$VV_RN_WORK"
printf '{"private":true}' > "$VV_RN_WORK/package.json"
npm install --prefix "$VV_RN_WORK" --cache "$VV_RN_WORK/npm-cache" --ignore-scripts --no-audit --no-fund \
  react@19.1.0 react-native@0.81.5 @react-native/metro-config@0.81.5 @react-native/babel-preset@0.81.5 metro@0.83.3
curl -fL https://github.com/getpaseo/paseo/releases/download/v0.10.2/paseo-v0.10.2-android.apk -o "$VV_RN_WORK/paseo.apk"
VV_RN_WORK="$VV_RN_WORK" VV_EMULATOR_SERIAL=emulator-5580 bash test/react-native-paseo/run.sh
```

The runner verifies the pinned host SHA256
`1f174879b2bb761cd689b7743d1eac91fa0513f6df803ad31d4baa7409eb6580`.
Expect 18 cases: four primary labels × original/moved layout × short/multiline,
plus disabled and ambiguous RN controls. Each successful case asserts exactly
one callback containing the exact synthetic draft. Result/build/log files stay
in VV_RN_WORK, never public/.

To reproduce the 1.2.17 geometry failure using the same host and synthetic UI,
rerun with `VV_RN_REV=204901d`. That comparison asserts the standard control sends,
then the moved, functional control is rejected with `Inserted — send manually`.
The revision is an explicit baseline, not an arbitrary historical test API.
The separate JVM regression pins the same release and demonstrates a cached
`enabled=false` child snapshot preventing selection until the native control is
refreshed. It is a controlled cache reproduction, not a claim that the phone's
current failure has been diagnosed.
