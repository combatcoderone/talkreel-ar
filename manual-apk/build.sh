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

gradle --no-daemon :app:assembleDebug :app:lintDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -s "$APK" ]; then
  echo "Build completed without producing the expected APK: $APK" >&2
  exit 1
fi

"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$APK"
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose "$APK"
sha256sum "$APK"
echo "Debug APK: $APK"
