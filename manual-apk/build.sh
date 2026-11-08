#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

# TalkReel 0.9.5+ uses bundled ML Kit face contours and Media3.
# A dependency-free javac/d8 build is no longer valid for this source tree.
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.13+ is required. Install Gradle and Android SDK 36." >&2
  exit 1
fi
gradle --no-daemon assembleDebug
APK="build/outputs/apk/debug/manual-apk-debug.apk"
if [ ! -f "$APK" ]; then
  APK_PATH=$(find build/outputs/apk/debug -maxdepth 1 -name "*.apk" -print -quit)
else
  APK_PATH="$APK"
fi
if [ -z "${APK_PATH:-}" ] || [ ! -f "$APK_PATH" ]; then
  echo "Gradle completed but no debug APK was found." >&2
  exit 1
fi
cp "$APK_PATH" app-debug.apk
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -n "$SDK" ] && [ -x "$SDK/build-tools/36.0.0/apksigner" ]; then
  "$SDK/build-tools/36.0.0/apksigner" verify --verbose app-debug.apk
fi
ls -lh app-debug.apk
echo "Done: app-debug.apk (debug/test signing only)"
