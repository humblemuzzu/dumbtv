#!/usr/bin/env bash
# Builds and installs dumbtv fixer and grants it WRITE_SECURE_SETTINGS. From the next boot on it
# switches ADB debugging off and on once, which brings network adb back on TVs where it stays
# dead after a reboot.
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
echo "installed $PKG; it switches ADB debugging off and on 20 s after each boot (logcat tag dumbtv-fixer)"
