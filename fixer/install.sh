#!/usr/bin/env bash
# Builds and installs dumbtv fixer, grants it WRITE_SECURE_SETTINGS and opts in to keep-adb-on.
# From the next boot on it turns ADB debugging back on after Fire OS switches it off during boot
# (or switches it off and on when it is on), so network adb survives reboots.
#   ./fixer/install.sh          build, install, grant
#   ./fixer/install.sh --undo   uninstall it
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.dumbtv.fixer
ADB=${ADB:-adb}
adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }

state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }

if [[ ${1:-} == --undo ]]; then
  adb_ uninstall $PKG
  exit 0
fi
[[ -z ${1:-} ]] || { echo "usage: $0 [--undo]" >&2; exit 2; }

./build.sh
out=$(adb_ install -r build/dumbtv-fixer.apk 2>&1 | grep -m1 -E 'Success|Failure|failed' || true)
[[ $out == *Success* ]] || { echo "error: adb install failed: $out" >&2; exit 1; }
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
granted=$(sh_ dumpsys package $PKG | grep -c 'WRITE_SECURE_SETTINGS: granted=true' || true)
[[ $granted -ge 1 ]] || { echo "error: WRITE_SECURE_SETTINGS was not granted" >&2; exit 1; }
# The owner's opt-in to have ADB turned back on after Fire OS switches it off during boot.
# Remove it to stop that: adb shell rm /sdcard/Android/media/$PKG/keep-adb-on
sh_ "mkdir -p /sdcard/Android/media/$PKG && touch /sdcard/Android/media/$PKG/keep-adb-on"
echo "installed $PKG; after each boot it turns ADB back on / reopens port 5555 (log: /sdcard/Android/media/$PKG/fixer.log; test now: adb shell am broadcast -n $PKG/.NowReceiver)"
