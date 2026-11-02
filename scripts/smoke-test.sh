#!/usr/bin/env bash
# Smoke test on a connected device or a running emulator: build and install the debug APK, start the game
# from a clean state and check that it comes up (the GPU is ready, the import screen asks for the disc
# image) without a panic and without the process dying.
#
#   scripts/smoke-test.sh [auto|vulkan|gl] [seconds]
#
# auto (default) lets the app choose its graphics backend; vulkan or gl force one through the system
# property debug.monkeyball.backend. `seconds` is how long the game must stay up (default 10).
# Exit code 0 = passed. The app's log (tag monkeyball) and any crash are printed either way.
set -euo pipefail

BACKEND="${1:-auto}"
SECONDS_UP="${2:-10}"
PKG=io.github.rmesek.monkeyball
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SDK="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$ADB.exe"
[ -x "$ADB" ] || { echo "adb not found under $SDK (set ANDROID_HOME)"; exit 2; }
# Gradle needs a JDK; Android Studio's own is the one the project is built with.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
    export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
fi

"$ADB" get-state >/dev/null 2>&1 || { echo "no device: start an emulator or connect a phone"; exit 2; }

echo "== build and install"
(cd "$ROOT" && ./gradlew --quiet installDebug)

echo "== start ($BACKEND)"
"$ADB" shell am force-stop "$PKG"
# A clean state: no game data, so the import screen and the document picker come up.
"$ADB" shell pm clear "$PKG" >/dev/null
if [ "$BACKEND" = auto ]; then
    "$ADB" shell setprop debug.monkeyball.backend "''"
else
    "$ADB" shell setprop debug.monkeyball.backend "$BACKEND"
fi
"$ADB" logcat -c
"$ADB" shell am start -W -n "$PKG/.MainActivity" >/dev/null

# Wait for the GPU (the "adapter:" line), then keep watching for the rest of the time.
result=""
for ((i = 0; i < SECONDS_UP * 2; i++)); do
    sleep 0.5
    log="$("$ADB" logcat -d -s monkeyball:V RustPanic:V AndroidRuntime:E DEBUG:F)"
    if grep -qE "panic|RustPanic|FATAL|error:" <<<"$log"; then result="crashed"; break; fi
    if [ -z "$("$ADB" shell pidof "$PKG" | tr -d '\r')" ]; then result="process ended"; break; fi
done
log="$("$ADB" logcat -d -s monkeyball:V RustPanic:V AndroidRuntime:E DEBUG:F)"
if [ -z "$result" ] && ! grep -q "adapter:" <<<"$log"; then result="no GPU adapter after ${SECONDS_UP}s"; fi

echo "== log"
echo "$log"
"$ADB" shell am force-stop "$PKG"
if [ -n "$result" ]; then
    echo "== FAILED: $result"
    exit 1
fi
echo "== PASSED: up for ${SECONDS_UP}s on $(grep -o 'adapter: .*' <<<"$log" | head -1)"
