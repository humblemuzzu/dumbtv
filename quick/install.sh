#!/usr/bin/env bash
# Builds and installs dumbtv quick settings, the panel the remote's settings button opens, and
# grants what it needs over adb (none of it can be granted on the TV):
#   brightness   appops WRITE_SETTINGS allow
#   warmth       appops SYSTEM_ALERT_WINDOW allow
#   video format pm grant READ_LOGS (it reads the display service's log tag only)
#   sleep timer  dpm set-active-admin (force-lock only)
#   noise meter  pm grant RECORD_AUDIO, only with --mic (or MIC=1)
#   cog button   pm grant WRITE_SECURE_SETTINGS, for Amazon's own quick_settings_lock: while the
#                panel is switched on, Amazon's quick settings ignores the cog (AmazonQs.java)
#   ./quick/install.sh          build, install, grant
#   ./quick/install.sh --mic    the same, plus the microphone for the noise row
#   ./quick/install.sh --undo   give up the device admin, then uninstall
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PKG=tv.dumbtv.quick
ADB=${ADB:-adb}
adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }

state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }

if [[ ${1:-} == --undo ]]; then
  if sh_ pm list packages "$PKG" | grep -q "package:$PKG\$"; then
    # Android refuses to uninstall an active device admin, and dpm remove-active-admin only
    # removes test admins, so the app gives it up itself (Undo.java).
    sh_ am broadcast -n "$PKG/.Undo" >/dev/null
    sleep 1
    adb_ uninstall "$PKG"
  else
    echo "$PKG is not installed"
  fi
  # Undo.java takes the lock off; this covers an app that was already gone or never answered.
  # Amazon reads a missing setting as 0, which is how a Fire TV comes.
  if [[ $(sh_ settings get secure quick_settings_lock | tr -d '\r') == 1 ]]; then
    sh_ settings delete secure quick_settings_lock >/dev/null
    echo "Amazon's quick settings lock taken off"
  fi
  exit 0
fi
[[ -z ${1:-} || ${1:-} == --mic ]] || { echo "usage: $0 [--mic | --undo]" >&2; exit 2; }

./build.sh
out=$(adb_ install -r build/dumbtv-quick.apk 2>&1 | grep -m1 -E 'Success|Failure|failed' || true)
[[ $out == *Success* ]] || { echo "error: adb install failed: $out" >&2; exit 1; }

sh_ appops set $PKG WRITE_SETTINGS allow
sh_ appops set $PKG SYSTEM_ALERT_WINDOW allow
sh_ pm grant $PKG android.permission.READ_LOGS
sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS 2>/dev/null ||
  echo "warning: WRITE_SECURE_SETTINGS was not granted; Amazon's quick settings will still start on the cog, and the panel closes it" >&2
if [[ ${MIC:-0} == 1 || ${1:-} == --mic ]]; then
  sh_ pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null || true
fi
if ! sh_ dumpsys device_policy | grep -q "$PKG/"; then
  sh_ dpm set-active-admin "$PKG/.Admin" >/dev/null ||
    echo "warning: could not make it a device admin; the sleep timer will not turn the TV off" >&2
fi

# The TV health row's expected state, where the owner wrote one (see expected.example.conf)
if [[ -f expected.conf ]]; then
  sh_ mkdir -p /sdcard/Android/media/$PKG
  adb_ push expected.conf /sdcard/Android/media/$PKG/expected.conf >/dev/null || true
fi
# A grant reaches a running process only after a restart (READ_LOGS especially).
sh_ am force-stop $PKG
# The service is not exported; the boot receiver starts it from inside the app.
sh_ am broadcast -n "$PKG/.Boot" >/dev/null

granted=$(sh_ dumpsys package $PKG | grep -c 'READ_LOGS: granted=true' || true)
[[ $granted -ge 1 ]] || echo "warning: READ_LOGS was not granted; the video format will show the panel mode only" >&2
echo "installed $PKG; the remote's settings button opens it (and Menu on an HDMI input). Undo: $0 --undo"
