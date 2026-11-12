#!/usr/bin/env bash
set -euo pipefail

if ./gradlew connectedDebugAndroidTest --stacktrace; then
  exit 0
fi

# Keep diagnostics useful without printing temporary signed video URLs.
adb logcat -d -v brief ExoPlayerImplInternal:E TestRunner:I '*:S' |
  sed -E 's#https?://[^ ]+#[redacted-url]#g' |
  tail -n 180
exit 1
