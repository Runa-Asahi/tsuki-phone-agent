#!/usr/bin/env bash
# Build a non-debuggable, v1+v2+v3-signed sideload APK. Refuse to emit if verification fails.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
if [[ -d "$HOME/android/sdk/build-tools" ]]; then export ANDROID_HOME="$HOME/android/sdk"
elif [[ -d "$HOME/android/build-tools" ]]; then export ANDROID_HOME="$HOME/android"
fi
./gradlew :app:assembleRelease
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
"$ROOT/scripts/verify-apk-signed.sh" "$APK"
AAPT="$(ls "$ANDROID_HOME"/build-tools/*/aapt | tail -1)"
VN="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
VC="$("$AAPT" dump badging "$APK" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/honor-phone-agent-v${VN}.apk"
cp -f "$APK" "$OUT"
echo "BUILT $OUT (versionCode=$VC versionName=$VN)"
echo "$OUT"
