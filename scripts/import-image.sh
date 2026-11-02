#!/usr/bin/env bash
# Pick the disc image in the document picker the game opened, and wait until the import has written
# game.pack. For tests on an emulator or a phone without touching it: run after scripts/smoke-test.sh (which
# leaves the app at the picker), or after starting the app with no game data.
#
#   scripts/import-image.sh [file name part] [timeout seconds]
#
# The image must already be on the device (for example `adb push "<image>.rvz" /sdcard/Download/`) and the
# picker must show the folder it is in (it opens the last one used). Default name part: ".rvz".
# Exit code 0 = game.pack is there and the app has not crashed.
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash: keep /sdcard/... as it is

NAME="${1:-.rvz}"
TIMEOUT="${2:-60}"
PKG=io.github.rmesek.monkeyball

SDK="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || ADB="$ADB.exe"

# The picker may still be opening.
bounds=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
    "$ADB" shell uiautomator dump /sdcard/mb-ui.xml >/dev/null 2>&1 || true
    bounds="$("$ADB" shell cat /sdcard/mb-ui.xml 2>/dev/null | tr '>' '\n' |
        grep -F " text=\"" | grep -vF " text=\"\"" | grep -F "$NAME" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)" || true
    [ -n "$bounds" ] && break
    sleep 1
done
[ -n "$bounds" ] || { echo "no file matching '$NAME' in the picker"; exit 1; }
read -r x1 y1 x2 y2 <<<"$(grep -oE '[0-9]+' <<<"$bounds" | tr '\n' ' ')"
echo "== tap '$NAME' at $(((x1 + x2) / 2)),$(((y1 + y2) / 2))"
"$ADB" shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))

for ((i = 0; i < TIMEOUT; i++)); do
    sleep 1
    if "$ADB" logcat -d -s monkeyball:V RustPanic:V | grep -qE "panic|error:"; then
        "$ADB" logcat -d -s monkeyball:V RustPanic:V
        echo "== FAILED: the app reported an error"
        exit 1
    fi
    # The pack is written under a temporary name and renamed when complete.
    if "$ADB" shell run-as "$PKG" ls files/game.pack >/dev/null 2>&1; then
        echo "== PASSED: game.pack written after ${i}s"
        exit 0
    fi
done
echo "== FAILED: no game.pack after ${TIMEOUT}s"
exit 1
