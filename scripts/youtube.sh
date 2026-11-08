#!/usr/bin/env bash
# Replaces the stock YouTube app with SmartTube, which has no ad code at all, and
# takes YouTube out of the home screen. Ads cannot be removed from the stock app:
# YouTube serves them from the same hosts as the video, so a DNS filter has
# nothing to block. docs/YOUTUBE.md explains why.
# Usage: ./scripts/youtube.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

REMOVE=0
FORCE=0
args=()
for a in "$@"; do
  case $a in
    --remove) REMOVE=1 ;;
    --force)  FORCE=1 ;;
    *) args+=("$a") ;;
  esac
done
parse_flags "${args[@]+"${args[@]}"}"
need_device
load_conf

YOUTUBE=${YOUTUBE:-0}
if [[ $YOUTUBE == 0 && $FORCE == 0 ]]; then
  note "YOUTUBE=0 in dumbtv.conf — the YouTube app is left alone."
  note "To replace it, answer yes in ./scripts/configure.sh, or pass --force."
  exit 0
fi

PKG=org.smarttube.stable
YT=com.amazon.firetv.youtube
APK_URL=https://github.com/yuliskov/SmartTube/releases/download/32.56s/SmartTube_stable_32.56_universal.apk
# Pinned: releases are immutable, so this hash identifies the exact build.
APK_SHA256=4a35b90940612e048e4e9cb9e10379f7db1e84f30375994129f10a571ee04ce5
# versionCode of that build. Update it with the URL and hash.
APK_VERSION_CODE=2446
HOME_DIR=/sdcard/Android/data/tv.dumbtv.home/files

step "plan"
note "install SmartTube (MIT, open source, TV-only YouTube client with no ad code)"
if [[ $REMOVE == 1 ]]; then
  note "remove $YT: it lives in /data, so this is a real removal (Appstore reinstalls it)"
else
  note "disable $YT: reversible, the app stays installed"
fi
note "home screen: SmartTube in, YouTube out"
note "two settings need the remote once: Hide shorts everywhere, and the 1080p vp9 preset"
note "the APK comes from the SmartTube GitHub release and is checksum-verified"
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] download $APK_URL"
  note "[dry-run] adb install -r $PKG"
  note "[dry-run] pm disable-user --user 0 $YT"
  exit 0
fi
confirm "Replace YouTube with SmartTube on $SERIAL?" || die "cancelled"

step "download"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-yt.XXXXXX") || die "could not create a temp dir"
trap 'rm -rf "$WORK"' EXIT
APK=$WORK/smarttube.apk
fetch "$APK" "$APK_URL" || die "could not download $APK_URL"
if command -v sha256sum >/dev/null; then got=$(sha256sum "$APK" | awk '{print $1}')
else got=$(shasum -a 256 "$APK" | awk '{print $1}'); fi
[[ $got == "$APK_SHA256" ]] || die "checksum mismatch for $APK_URL (got $got) — not installing"
note "checksum ok"

step "app"
installed_code() { dev "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
# adb's last line is not proof either way: over Wi-Fi the session can drop after the
# ~30 MB push but before "Success" prints, and the app is installed anyway. That made
# the first run fail and a re-run pass. What the TV reports afterwards is the answer;
# a SmartTube newer than the pinned build (updated from inside the app) also counts.
for attempt in 1 2; do
  out=$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -1)
  printf '  %s\n' "$out"
  adb_reconnect
  code=$(installed_code)
  [[ ${code:-0} -ge $APK_VERSION_CODE ]] && break
  [[ $attempt == 1 ]] && note "not installed yet; trying once more"
done
[[ ${code:-0} -ge $APK_VERSION_CODE ]] || die "SmartTube is not installed after two attempts: $out"
note "SmartTube installed (versionCode $code)"

step "stock app"
if present "$YT"; then
  if [[ $REMOVE == 1 ]]; then
    dev "pm uninstall --user 0 $YT" | tail -1
    if present "$YT"; then
      note "could not remove $YT — update it from the Appstore and run this again"
    else
      note "removed (Appstore > YouTube reinstalls it)"
    fi
  else
    dev "pm disable-user --user 0 $YT" | tail -1
    if grep -qx "package:$YT" <<<"$(dev "pm list packages -d" | tr -d '\r')"; then
      note "disabled (the app is still installed: 'pm enable $YT' brings it back)"
    else
      note "could not disable $YT — update it from the Appstore and run this again"
    fi
  fi
else
  note "$YT is not installed"
fi

step "home screen"
if ! present tv.dumbtv.home; then
  note "dumbtv home is not installed; skipping the home-screen step"
elif [[ $SDK -ge 30 ]]; then
  # Fire OS 8 closes Android/data to adb, so the app order cannot be read, and a file pushed
  # into the media folder would replace it instead of editing it. The launcher puts an app
  # with no place in the order after the ordered ones, and a disabled YouTube drops out.
  note "SmartTube is at the end of the apps row; to move it, hold OK on it and pick Move"
else
  conf=$WORK/home.conf
  if "$ADB" -s "$SERIAL" shell "cat $HOME_DIR/home.conf" > "$conf" 2>/dev/null && [[ -s $conf ]]; then
    # Keep every other key; replace the app order, then drop YouTube from it. A CR that adb
    # left on a line would keep a last-listed YouTube from matching, so it goes first.
    tr -d '\r' < "$conf" | awk -v pkg="$PKG" -v yt="$YT" '
      /^apps\.order=/ {
        n=split(substr($0, index($0,"=")+1), a, ",")
        out=pkg
        for (i=1;i<=n;i++) { gsub(/^[ \t]+|[ \t]+$/, "", a[i]); if (a[i] != "" && a[i] != pkg && a[i] != yt) out=out","a[i] }
        print "apps.order=" out
        done=1
        next
      }
      { print }
      END { if (!done) print "apps.order=" pkg }
    ' > "$conf.new"
    "$ADB" -s "$SERIAL" push "$conf.new" "$HOME_DIR/home.conf" | tail -1
    note "apps.order now: $(sed -n 's/^apps\.order=//p' "$conf.new")"
  else
    note "no home.conf yet — set the app order in dumbtv home > Options"
  fi
fi

step "one-time settings (remote, on the TV)"
note "left drawer > Settings > General > Hide content > tick 'Hide shorts everywhere'"
note "Settings > Player > Video presets > 1080p 60fps vp9 (not av01 — no hardware decoder)"

step "verify"
dev "dumpsys package $PKG" | grep -m1 versionName | sed 's/^ */  /'
if ! present "$YT"; then note "YouTube: not installed"
elif grep -qx "package:$YT" <<<"$(dev "pm list packages -d" | tr -d '\r')"; then note "YouTube: disabled"
else note "YouTube: still installed and enabled"; fi
out=$("$ADB" -s "$SERIAL" shell "monkey -p $PKG -c android.intent.category.LEANBACK_LAUNCHER 1" 2>&1)
case $out in
  *injected*|*injected:*) note "SmartTube launches" ;;
  *) note "could not launch SmartTube: $(printf '%s' "$out" | tail -1)" ;;
esac
