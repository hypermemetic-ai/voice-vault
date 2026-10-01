#!/usr/bin/env bash
# Builds a TEST-ONLY, offline fixture incorporating the unmodified production service.
set -euo pipefail
# Match the release builder's Java version (old d8 rejects newer javac metadata).
for JDK in openjdk@17 openjdk@21 openjdk; do
    if [[ -d "/home/linuxbrew/.linuxbrew/opt/$JDK/bin" ]]; then
        export PATH="/home/linuxbrew/.linuxbrew/opt/$JDK/bin:$PATH"; break
    fi
done
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
TOOLS="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
BUILD="${VV_NATIVE_OUT:-$(mktemp -d /tmp/vv-native-paseo.XXXXXX)}"
mkdir -p "$BUILD/gen" "$BUILD/classes"
echo "Native evidence/build directory: $BUILD"
"$TOOLS/aapt2" compile --dir "$ROOT/android/res" -o "$BUILD/res.zip"
"$TOOLS/aapt2" link -I "$JAR" --manifest "$ROOT/test/native-paseo/AndroidManifest.xml" --custom-package ai.hypermemetic.voicevault --java "$BUILD/gen" -o "$BUILD/base.apk" "$BUILD/res.zip"
javac --release 11 -cp "$JAR" -d "$BUILD/classes" "$BUILD/gen/ai/hypermemetic/voicevault/R.java" "$ROOT"/android/src/ai/hypermemetic/voicevault/*.java "$ROOT"/test/native-paseo/*.java
"$TOOLS/d8" --min-api 26 --lib "$JAR" --output "$BUILD" "$BUILD/classes/ai/hypermemetic/voicevault/"*.class
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q -u unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$TOOLS/apksigner" sign --ks "$ROOT/android/release.keystore" --ks-pass pass:voicevault --key-pass pass:voicevault --ks-key-alias voicevault --out "$BUILD/fixture.apk" "$BUILD/aligned.apk"
[[ "${1:-}" != --build-only ]] || exit 0
SERIAL="${VV_EMULATOR_SERIAL:?Set VV_EMULATOR_SERIAL to an isolated, booted emulator serial}"
[[ "$SERIAL" == emulator-* ]] || { echo 'Refusing non-emulator target' >&2; exit 1; }
ADB=("$SDK/platform-tools/adb" -s "$SERIAL")
[[ "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || { echo 'Emulator not booted' >&2; exit 1; }
[[ "$("${ADB[@]}" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Offline hardware-key verification requires root adb on this isolated emulator' >&2; exit 1; }
if "${ADB[@]}" shell pm path sh.paseo.debug | grep -q '^package:'; then
    echo 'Refusing to overwrite an existing Paseo/fixture package; use an isolated emulator.' >&2; exit 1
fi
# No server restarts, no release/phone install. Restore emulator settings afterward.
OLD_SERVICES="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
OLD_ENABLED="$("${ADB[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"
cleanup() {
    if [[ -z "$OLD_SERVICES" || "$OLD_SERVICES" == null ]]; then
        "${ADB[@]}" shell settings delete secure enabled_accessibility_services || true
    else
        "${ADB[@]}" shell settings put secure enabled_accessibility_services "$OLD_SERVICES" || true
    fi
    if [[ -z "$OLD_ENABLED" || "$OLD_ENABLED" == null ]]; then
        "${ADB[@]}" shell settings delete secure accessibility_enabled || true
    else
        "${ADB[@]}" shell settings put secure accessibility_enabled "$OLD_ENABLED" || true
    fi
    "${ADB[@]}" uninstall sh.paseo.debug > "$BUILD/uninstall.log" || true
}
trap cleanup EXIT
"${ADB[@]}" install -r "$BUILD/fixture.apk"
"${ADB[@]}" shell appops set sh.paseo.debug SYSTEM_ALERT_WINDOW allow
# Instrumentation enables the service after am instrument's target force-stop.
"${ADB[@]}" shell settings delete secure enabled_accessibility_services
"${ADB[@]}" logcat -c
# Bounded instrumentation and diagnostic logs; APK never enters public/release output.
set +e
timeout --kill-after=5s 240s python3 "$ROOT/test/native-paseo/hardware-keys.py" "$SDK/platform-tools/adb" "$SERIAL" "$BUILD/instrumentation.log"
RESULT=$?
set -e
"${ADB[@]}" logcat -d > "$BUILD/logcat.log"
[[ "$RESULT" == 0 ]] && grep -q 'OK native cases=' "$BUILD/instrumentation.log"
