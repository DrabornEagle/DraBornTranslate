#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root/console-ui"
flutter pub get --enforce-lockfile
flutter build aar --no-profile --target-platform android-arm64,android-x64 --build-number 1.0
test -f build/host/outputs/repo/com/draborneagle/draborn_console/flutter_release/1.0/flutter_release-1.0.aar
test -f build/host/outputs/repo/com/draborneagle/draborn_console/flutter_debug/1.0/flutter_debug-1.0.aar
