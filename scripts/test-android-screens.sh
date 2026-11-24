#!/usr/bin/env bash
set -euo pipefail
mkdir -p screen-tests
collect() {
    adb pull /sdcard/Android/data/com.draborneagle.draborntranslate.v5/files/ screen-tests/screens/ || true
    adb logcat -d -s AndroidRuntime TestRunner > screen-tests/android.log || true
}
trap collect EXIT
./gradlew --no-daemon -PskipPs5 -PtargetAbi=x86_64 -PtozModelDir="$RUNNER_TEMP/toz-model" :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.draborneagle.draborntranslate.v5.test/androidx.test.runner.AndroidJUnitRunner \
    | tee screen-tests/tests.log
grep -Eq '^OK \([1-9][0-9]* tests?\)' screen-tests/tests.log
