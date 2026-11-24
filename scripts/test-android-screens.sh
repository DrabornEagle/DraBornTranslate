#!/usr/bin/env bash
set -euo pipefail
mkdir -p screen-tests
collect() {
    adb pull /sdcard/Android/data/com.draborneagle.draborntranslate.v5/files/ screen-tests/screens/ || true
    adb logcat -d -s AndroidRuntime TestRunner > screen-tests/android.log || true
}
trap collect EXIT
./gradlew --no-daemon -PskipPs5 -PtargetAbi=x86_64 -PtozModelDir="$RUNNER_TEMP/toz-model" :app:connectedDebugAndroidTest
