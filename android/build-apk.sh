#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Ensure Java is in PATH
if [[ -d "/home/linuxbrew/.linuxbrew/opt/openjdk@17/bin" ]]; then
    export PATH="/home/linuxbrew/.linuxbrew/opt/openjdk@17/bin:$PATH"
elif [[ -d "/home/linuxbrew/.linuxbrew/opt/openjdk@21/bin" ]]; then
    export PATH="/home/linuxbrew/.linuxbrew/opt/openjdk@21/bin:$PATH"
elif [[ -d "/home/linuxbrew/.linuxbrew/opt/openjdk/bin" ]]; then
    export PATH="/home/linuxbrew/.linuxbrew/opt/openjdk/bin:$PATH"
fi

if ! command -v javac >/dev/null 2>&1; then
    echo "Error: javac not found in PATH." >&2
    exit 1
fi

ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk}}"
if [[ ! -d "$ANDROID_SDK" ]]; then
    echo "Error: Android SDK not found at $ANDROID_SDK." >&2
    exit 1
fi

BUILD_TOOLS_DIR="$ANDROID_SDK/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_SDK/platforms/android-34/android.jar"

AAPT2="$BUILD_TOOLS_DIR/aapt2"
D8="$BUILD_TOOLS_DIR/d8"
ZIPALIGN="$BUILD_TOOLS_DIR/zipalign"
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"

echo "==> Using Android SDK at: $ANDROID_SDK"
echo "==> Using Java: $(command -v javac)"

BUILD_DIR="$SCRIPT_DIR/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/compiled_res" "$BUILD_DIR/classes"

# 1. Compile resources with aapt2
echo "==> Compiling resources..."
"$AAPT2" compile --dir res -o "$BUILD_DIR/compiled_res.zip"

# 2. Link resources and generate R.java and initial APK
echo "==> Linking resources..."
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -o "$BUILD_DIR/base.apk" \
    --java "$BUILD_DIR/gen" \
    --auto-add-overlay \
    "$BUILD_DIR/compiled_res.zip"

# 3. Compile Java sources
echo "==> Compiling Java classes..."
javac \
    --release 11 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    "$BUILD_DIR/gen/ai/hypermemetic/voicevault/R.java" \
    src/ai/hypermemetic/voicevault/*.java

# 4. Dex classes with d8
echo "==> Dexing classes with d8..."
"$D8" \
    --min-api 26 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR" \
    "$BUILD_DIR/classes/ai/hypermemetic/voicevault/"*.class

# 5. Add classes.dex into unaligned APK
echo "==> Packaging APK..."
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unaligned.apk"
(cd "$BUILD_DIR" && zip -u unaligned.apk classes.dex)

# 6. Align APK
echo "==> Aligning APK..."
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/VoiceVault-uncompressed.apk"

# 7. Keystore & Signing
KEYSTORE="$SCRIPT_DIR/release.keystore"
KEYSTORE_PASS="voicevault"
KEY_ALIAS="voicevault"

if [[ ! -f "$KEYSTORE" ]]; then
    # Never auto-generate: a different certificate makes Android refuse to
    # update an installed app (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    echo "Error: release keystore not found at $KEYSTORE" >&2
    echo "It is tracked in git - restore it with: git checkout -- android/release.keystore" >&2
    exit 1
fi

FINAL_APK="$BUILD_DIR/VoiceVault.apk"
echo "==> Signing APK..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$FINAL_APK" \
    "$BUILD_DIR/VoiceVault-uncompressed.apk"

echo "==> Verifying signature..."
"$APKSIGNER" verify --verbose "$FINAL_APK"

echo "==> Successfully built signed APK: $FINAL_APK"
ls -lh "$FINAL_APK"

# Copy to public web directory for 1-tap download
mkdir -p "$SCRIPT_DIR/../public"
cp "$FINAL_APK" "$SCRIPT_DIR/../public/voice-vault.apk"
echo "==> Copied to public web directory: $SCRIPT_DIR/../public/voice-vault.apk"
