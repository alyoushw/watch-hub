#!/usr/bin/env bash
set -euo pipefail

smoke_exit_code=0
./gradlew connectedDebugAndroidTest --stacktrace || smoke_exit_code=$?
if [ "$smoke_exit_code" -ne 0 ]; then
  adb logcat -d -v brief ExoPlayerImplInternal:E TestRunner:I '*:S' |
    sed -E 's#https?://[^ ]+#[redacted-url]#g' |
    tail -n 180
fi

smoke_package="com.alyoushw.watchhub"
if [ "${WATCHHUB_PREVIEW:-false}" = "true" ]; then smoke_package="$smoke_package.animepreview"; fi
mkdir -p app/build/reports/screenshots
adb pull "/sdcard/Android/data/$smoke_package/files/screenshots/." app/build/reports/screenshots/ 2>/dev/null || true
exit "$smoke_exit_code"
