#!/usr/bin/env bash
# Optional, isolated RN host test. Never installs on a phone or overwrites Paseo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
TOOLS="$SDK/build-tools/34.0.0"
WORK="${VV_RN_WORK:?Set VV_RN_WORK to a temporary directory with the pinned host APK and RN libraries}"
SERIAL="${VV_EMULATOR_SERIAL:?Set VV_EMULATOR_SERIAL to a disposable emulator}"
[[ "$SERIAL" == emulator-* ]] || { echo 'Refusing non-emulator target' >&2; exit 1; }
WORK="$(realpath "$WORK")"
[[ "$WORK" != "$ROOT" && "$WORK" != "$ROOT/"* ]] || { echo 'Use a temporary directory outside the checkout' >&2; exit 1; }
[[ -d "$WORK/node_modules/@react-native/metro-config" ]] || { echo 'Missing optional RN libraries; see README' >&2; exit 1; }
[[ ! -L "$WORK/node_modules" ]] || { echo 'Install optional libraries in VV_RN_WORK; Metro cannot use this symlinked dependency root' >&2; exit 1; }
echo '1f174879b2bb761cd689b7743d1eac91fa0513f6df803ad31d4baa7409eb6580  paseo.apk' | (cd "$WORK" && sha256sum -c -)
ADB=("$SDK/platform-tools/adb" -s "$SERIAL")
[[ "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || { echo 'Emulator not booted' >&2; exit 1; }
for PKG in sh.paseo sh.paseo.debug; do
    if "${ADB[@]}" shell pm path "$PKG" | rg -q '^package:'; then
        echo "Refusing existing $PKG; use a clean disposable emulator" >&2; exit 1
    fi
done
cp "$ROOT/test/react-native-paseo/composer.js" "$WORK/index.js"
cat > "$WORK/babel.config.js" <<'JS'
module.exports = {presets: ['module:@react-native/babel-preset']};
JS
cat > "$WORK/bundle.cjs" <<'JS'
const Metro = require('metro');
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
(async () => {
  const config = mergeConfig(getDefaultConfig(__dirname), {projectRoot: __dirname, maxWorkers: 2, cacheStores: [], watchFolders: [], useWatchman: false});
  await Metro.runBuild(config, {entry: 'index.js', out: __dirname + '/index.android.bundle', platform: 'android', dev: false, minify: true});
})().catch(e => {console.error(e); process.exit(1);});
JS
(cd "$WORK" && node bundle.cjs > bundle.log 2>&1)
python3 - "$ROOT" "$WORK" "${VV_RN_REV:-}" <<'PY'
import pathlib, shutil, subprocess, sys, zipfile
root, work = map(pathlib.Path, sys.argv[1:3]); revision = sys.argv[3]
service = work/'service'
shutil.copytree(root/'android', service/'android', dirs_exist_ok=True, ignore=shutil.ignore_patterns('build'))
shutil.copytree(root/'test/native-paseo', service/'test/native-paseo', dirs_exist_ok=True)
shutil.copyfile(root/'test/react-native-paseo/PaseoFixtureTest.java', service/'test/native-paseo/PaseoFixtureTest.java')
if revision:
    for name in ['android/src/ai/hypermemetic/voicevault/VoiceVaultKeyService.java', 'android/src/ai/hypermemetic/voicevault/PaseoSelection.java', 'android/res/xml/accessibility_service_config.xml']:
        (service/name).write_bytes(subprocess.check_output(['git','show',revision+':'+name], cwd=root))
with zipfile.ZipFile(work/'paseo.apk') as source, zipfile.ZipFile(work/'host-unsigned.apk', 'w') as target:
    for entry in source.infolist():
        if entry.filename.startswith('META-INF/'): continue
        data = source.read(entry.filename)
        if entry.filename == 'assets/index.android.bundle': data = (work/'index.android.bundle.js').read_bytes()
        if entry.filename == 'AndroidManifest.xml':
            # Same-length binary XML string substitutions remove these permissions.
            for old, new in [('android.permission.INTERNET','android.permission.INTERNEX'), ('android.permission.RECORD_AUDIO','android.permission.NEVER_RECORD'), ('android.permission.CAMERA','android.permission.CAMERX')]:
                assert len(old) == len(new)
                assert old.encode() in data or old.encode('utf-16le') in data
                for encoding in ['utf-8','utf-16le']: data = data.replace(old.encode(encoding), new.encode(encoding))
        target.writestr(entry, data)
PY
VV_NATIVE_OUT="$WORK/service-build" bash "$WORK/service/test/native-paseo/run.sh" --build-only > "$WORK/service-build.log" 2>&1
"$TOOLS/zipalign" -f -p 4 "$WORK/host-unsigned.apk" "$WORK/host-aligned.apk"
"$TOOLS/apksigner" sign --ks "$ROOT/android/release.keystore" --ks-pass pass:voicevault --key-pass pass:voicevault --ks-key-alias voicevault --out "$WORK/host-test-only.apk" "$WORK/host-aligned.apk"
"$TOOLS/aapt" dump permissions "$WORK/host-test-only.apk" > "$WORK/host-permissions.txt"
if rg -q "name='android.permission.(INTERNET|RECORD_AUDIO|CAMERA)'" "$WORK/host-permissions.txt"; then
    echo 'Host test permissions were not removed' >&2; exit 1
fi
OLD_SERVICES="$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
OLD_ENABLED="$("${ADB[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"
cleanup() {
    if [[ "$OLD_SERVICES" == null || -z "$OLD_SERVICES" ]]; then "${ADB[@]}" shell settings delete secure enabled_accessibility_services;
    else "${ADB[@]}" shell settings put secure enabled_accessibility_services "$OLD_SERVICES"; fi
    if [[ "$OLD_ENABLED" == null || -z "$OLD_ENABLED" ]]; then "${ADB[@]}" shell settings delete secure accessibility_enabled;
    else "${ADB[@]}" shell settings put secure accessibility_enabled "$OLD_ENABLED"; fi
    "${ADB[@]}" uninstall sh.paseo.debug > "$WORK/uninstall-service.log" || true
    "${ADB[@]}" uninstall sh.paseo > "$WORK/uninstall-host.log" || true
}
trap cleanup EXIT
"${ADB[@]}" install --no-incremental "$WORK/host-test-only.apk" > "$WORK/install-host.log"
"${ADB[@]}" install --no-incremental "$WORK/service-build/fixture.apk" > "$WORK/install-service.log"
"${ADB[@]}" shell appops set sh.paseo.debug SYSTEM_ALERT_WINDOW allow
"${ADB[@]}" shell settings delete secure enabled_accessibility_services
"${ADB[@]}" logcat -c
ARGS=(); [[ -z "${VV_RN_REV:-}" ]] || ARGS=(-e baseline true)
timeout --kill-after=5s 120s "${ADB[@]}" shell am instrument "${ARGS[@]}" -w sh.paseo.debug/ai.hypermemetic.voicevault.PaseoFixtureTest | tee "$WORK/instrumentation.log"
"${ADB[@]}" logcat -d > "$WORK/logcat.log"
rg -q 'OK React Native cases=' "$WORK/instrumentation.log"
