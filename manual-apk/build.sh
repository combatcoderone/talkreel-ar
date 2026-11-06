#!/usr/bin/env bash
set -euo pipefail

# TalkReel AR 0.11.0 now depends on ARCore and must be built through Gradle.
# Requirements: JDK 17, Gradle 8.13, Android SDK platform 36 and build-tools 36.0.0.
# The GitHub Actions workflow is the canonical reproducible build.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.13 is required. Install Gradle or build from Android Studio." >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to your Android SDK." >&2
  exit 1
fi
BT="$SDK/build-tools/36.0.0"

gradle --no-daemon :app:assembleDebug :app:lintDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -s "$APK" ]; then
  echo "Build completed without producing the expected APK: $APK" >&2
  exit 1
fi

"$BT/aapt" dump badging "$APK"
"$BT/apksigner" verify --verbose "$APK"
sha256sum "$APK"
echo "Debug APK: $APK"
