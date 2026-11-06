#!/bin/bash
set -e
cd "$(dirname "$0")"
# TalkReel manual APK build, no Gradle.
# Set these in your environment before running:
#   JAVA_HOME=/path/to/jdk-17
#   ANDROID_SDK_ROOT=/path/to/android-sdk
#   TALKREEL_KEYSTORE_PASS=your-keystore-password
# If JAVA_HOME or ANDROID_SDK_ROOT are not set, the script falls back to
# $HOME/workspace/.jdk and $HOME/workspace/.android-sdk, which is only the
# local build machine layout, not a requirement.
# The keystore password is NEVER hardcoded here, and the script does NOT
# auto-generate a keystore. Create your own release keystore first and put
# it at manual-apk/release.keystore. Never commit a keystore or a password.
JDK="${JAVA_HOME:-$HOME/workspace/.jdk}"
SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/.android-sdk}"
if [ -z "$TALKREEL_KEYSTORE_PASS" ]; then
  echo "Error: set TALKREEL_KEYSTORE_PASS in your environment." >&2
  exit 1
fi
if [ ! -f release.keystore ]; then
  echo "Error: release.keystore not found in manual-apk/. Create your own keystore first, do not commit it." >&2
  exit 1
fi
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
rm -rf out classes dex compiled.zip unsigned.apk aligned.apk app-debug.apk
mkdir -p out classes dex
echo "1. Compile resources"
"$BT/aapt2" compile --dir res -o compiled.zip
echo "2. Link APK"
"$BT/aapt2" link -o unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" compiled.zip --auto-add-overlay
echo "3. Compile Java"
javac -encoding UTF-8 --release 8 -cp "$ANDROID_JAR" -d classes $(find src -name "*.java")
echo "4. Dex"
jar --create --file app.jar -C classes .
"$BT/d8" --release --lib "$ANDROID_JAR" --output dex app.jar
echo "5. Add dex to APK"
python3 -c "import zipfile; z=zipfile.ZipFile('unsigned.apk','a'); z.write('dex/classes.dex','classes.dex'); z.close()"
echo "6. Align"
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
echo "7. Sign"
"$BT/apksigner" sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --ks release.keystore --ks-pass "pass:$TALKREEL_KEYSTORE_PASS" --key-pass "pass:$TALKREEL_KEYSTORE_PASS" --out app-debug.apk aligned.apk
"$BT/apksigner" verify app-debug.apk
ls -lh app-debug.apk
echo "Done: app-debug.apk"
