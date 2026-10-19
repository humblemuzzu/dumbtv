#!/usr/bin/env bash
# Installs dumbtv home on the TV and gives it the HOME button.
#   ./launcher/install.sh            download the release APK (checksum-verified), install, take over HOME
#   ./launcher/install.sh --build    build from source instead (needs a JDK and the Android SDK)
#   ./launcher/install.sh --undo     hand HOME back to Amazon's launcher
# Pick the device with ADB_SERIAL when more than one is connected.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck disable=SC1091
source version.env
APK_URL=https://github.com/humblemuzzu/dumbtv/releases/download/launcher-v$VERSION/dumbtv-home.apk

PKG=tv.dumbtv.home
GUARD=$PKG/$PKG.Guard
OLD_GUARD=com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService
ADB=${ADB:-adb}

adb_() { "$ADB" ${ADB_SERIAL:+-s "$ADB_SERIAL"} "$@"; }
sh_() { adb_ shell "$@" </dev/null; }
installed() { sh_ pm path "$1" 2>/dev/null | grep -q '^package:'; }

mode=release
case ${1:-} in
  "") ;;
  --build) mode=build ;;
  --undo) mode=undo ;;
  *) echo "usage: $0 [--build | --undo]" >&2; exit 2 ;;
esac

command -v "$ADB" >/dev/null || { echo "error: adb not found — install Android platform-tools" >&2; exit 1; }
state=$(adb_ get-state 2>/dev/null || true)
[[ $state == device ]] || { echo "error: no TV connected (adb connect <tv-ip>:5555; set ADB_SERIAL if several)" >&2; exit 1; }

# Alarms normally fire no sooner than 5 s from now; the home switch needs them immediate.
set_futurity() {
  local cur
  cur=$(sh_ settings get global alarm_manager_constants | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ',' '\n' | { grep -v '^min_futurity=' || true; } | paste -sd, -)
  if [[ $1 == on ]]; then cur=${cur:+$cur,}min_futurity=0; fi
  if [[ -n $cur ]]; then sh_ settings put global alarm_manager_constants "$cur"
  else sh_ settings delete global alarm_manager_constants >/dev/null; fi
}

# Fire OS 6's `settings` command silently drops writes to enabled_accessibility_services
# (exit code 0, value unchanged); the settings content provider still takes them.
put_secure() {
  local got
  sh_ settings put secure "$1" "${2:-\"\"}"
  got=$(sh_ settings get secure "$1" | tr -d '\r')
  [[ $got == "$2" || ( -z $2 && $got == null ) ]] && return 0
  sh_ content insert --uri content://settings/secure --bind "name:s:$1" --bind "value:s:$2"
}

# Puts $1 (or nothing) in place of any launcher's service, keeping every other service.
set_guard() {
  local cur
  cur=$(sh_ settings get secure enabled_accessibility_services | tr -d '\r')
  [[ $cur == null ]] && cur=
  cur=$(printf '%s' "$cur" | tr ':' '\n' | { grep -vxF -e "$GUARD" -e "$PKG/.Guard" -e "$OLD_GUARD" -e '' || true; } | paste -sd: -)
  if [[ -n ${1:-} ]]; then cur=${cur:+$cur:}$1; fi
  # Clearing first makes the accessibility manager rebind a service whose process died.
  put_secure enabled_accessibility_services ""
  sleep 1
  if [[ -n $cur ]]; then
    put_secure enabled_accessibility_services "$cur"
    put_secure accessibility_enabled 1
  else
    put_secure accessibility_enabled 0
  fi
}

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

if [[ $mode == undo ]]; then
  # force-stop also keeps the Fire OS 6 Keeper from restarting at the next boot.
  sh_ am force-stop $PKG >/dev/null 2>&1 || true
  sh_ appops set $PKG GET_USAGE_STATS default >/dev/null 2>&1 || true
  set_futurity off
  set_guard ""
  sh_ am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  echo "HOME handed back to Amazon's launcher (dumbtv home stays installed: adb uninstall $PKG)"
  exit 0
fi

# The release APK is built for Android 9+. Fire OS 6 (Android 7.1) gets a build from source.
dev_sdk=$(sh_ getprop ro.build.version.sdk | tr -d '\r')
case $dev_sdk in ''|*[!0-9]*) dev_sdk=28 ;; esac
if [[ $dev_sdk -lt 28 ]]; then
  [[ $mode == release ]] && echo "Fire OS 6 (sdk $dev_sdk): the release APK needs Android 9, building from source"
  mode=build
  export MIN_SDK=${MIN_SDK:-$dev_sdk}
fi

if [[ $mode == build ]]; then
  ./build.sh
  APK=build/dumbtv-home.apk
else
  mkdir -p build
  APK=build/dumbtv-home-$VERSION.apk
  if [[ ! -f $APK || $(sha256 "$APK") != "$APK_SHA256" ]]; then
    echo "downloading dumbtv home $VERSION"
    curl -fL --progress-bar -o "$APK.part" "$APK_URL" || { echo "error: could not download $APK_URL" >&2; exit 1; }
    mv "$APK.part" "$APK"
  fi
  got=$(sha256 "$APK")
  [[ $got == "$APK_SHA256" ]] || { echo "error: checksum mismatch for $APK (got $got) — not installing" >&2; exit 1; }
  echo "checksum ok"
fi

out=$(adb_ install -r --no-incremental "$APK" 2>&1 | tail -1 || true)
case $out in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
    echo "error: the installed dumbtv home was signed with a different key (release vs your own build)." >&2
    echo "  back up your settings:  adb pull /sdcard/Android/data/$PKG/files/home.conf" >&2
    echo "  then remove it:         adb uninstall $PKG    and run this again" >&2
    exit 1 ;;
  *) echo "error: adb install failed: $out" >&2; exit 1 ;;
esac
echo "installed $PKG"

sh_ dumpsys deviceidle whitelist +$PKG >/dev/null

# Fire OS 6.7 refuses third-party accessibility services (A11yAuthorizer), so the Guard
# cannot bind there. Keeper watches Amazon's home through UsageStats and calls
# moveTaskToFront instead, which is not subject to the 5 s app-switch delay.
if [[ $dev_sdk -lt 28 ]]; then
  set_guard ""
  sh_ appops set $PKG GET_USAGE_STATS allow
  sh_ am start -W -n $PKG/.Home >/dev/null
  sleep 3
  if ! sh_ dumpsys activity services $PKG 2>/dev/null | grep -q 'isForeground=true'; then
    echo "error: the Fire OS 6 home keeper did not start ($PKG/.Keeper)" >&2
    exit 1
  fi
  front=$(sh_ dumpsys activity activities | grep -m1 mResumedActivity || true)
  if [[ $front != *"$PKG"* ]]; then
    echo "error: dumbtv home is installed but not in front ($front)" >&2
    exit 1
  fi
  echo "home: dumbtv (Keeper, no accessibility; sdk $dev_sdk)"
  exit 0
fi

sh_ cmd package set-home-activity $PKG/.Home >/dev/null 2>&1 || true
set_futurity on
set_guard $GUARD
sleep 2
# The setting alone proves nothing: Fire OS decides whether the service is bound. On
# Fire OS 6.7 its A11yAuthorizer refuses services from packages Amazon does not trust.
# The dump names the service by its label, not its package, so match the label inside the
# current user's bound-services section (same parser verify.sh uses).
source ../scripts/accessibility.sh
user=$(sh_ am get-current-user | tr -d '\r')
bound=0
for _ in 1 2 3 4 5; do
  if sh_ dumpsys accessibility | guard_bound "${user:-0}"; then bound=1; break; fi
  sleep 1
done
if [[ $bound == 0 ]]; then
  echo "error: Fire OS did not bind the HOME guard ($GUARD)." >&2
  denied=$(sh_ logcat -d -b all | grep A11yAuthorizer | grep -c "$PKG" || true)
  if [[ ${denied:-0} -gt 0 ]]; then
    echo "  this build only runs accessibility services Amazon trusts (FireOSFwk A11yAuthorizer)," >&2
    echo "  so HOME cannot be taken over here. dumbtv home stays installed as a normal app;" >&2
    echo "  remove it with: adb uninstall $PKG" >&2
  fi
  set_futurity off
  set_guard ""
  exit 1
fi
sh_ am start -W -n $PKG/.Home >/dev/null
if installed com.spocky.projengmenu; then
  echo "note: Projectivy is still installed but no longer used — adb uninstall com.spocky.projengmenu"
fi

sleep 2
printf 'accessibility: %s\n' "$(sh_ settings get secure enabled_accessibility_services)"
printf 'front window:  %s\n' "$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed 's/.* u0 //; s/}$//')"
