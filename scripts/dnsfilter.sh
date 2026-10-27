#!/usr/bin/env bash
# Builds the blocklist on this machine, pushes it to the TV and installs the
# filter that uses it. No root, no router access, no account. Ends with a reboot
# and a self-test. Usage: ./scripts/dnsfilter.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$ROOT/scripts/appdata.sh"
source "$ROOT/scripts/appui.sh"
parse_flags "$@"
need_device
load_conf

if [[ $DNSFILTER == 0 ]]; then
  note "DNSFILTER=0 in dumbtv.conf — skipping. Re-run ./scripts/configure.sh to change that."
  exit 0
fi

PKG=dnsfilter.android
APK_VERSION=1506103
APK_URL=https://f-droid.org/repo/dnsfilter.android_$APK_VERSION.apk
# Pinned: F-Droid keeps one file per version, so this is stable. Update the hash
# deliberately when the version above changes.
APK_SHA256=3ddd0db322d1ca5fe950ea29dc1788af6e6581bb5f34694085fb4aa2e77b68ad
CONF_URL=https://raw.githubusercontent.com/IngoZenz/personaldnsfilter/master/app/src/main/assets/dnsfilter.conf
HAGEZI_URL=https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/native.amazon-onlydomains.txt
# Private work dir: fixed paths under /tmp can be pre-seeded by another local user.
WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv.XXXXXX") || die "could not create a temp dir"
trap 'rm -rf "$WORK"' EXIT
APK=$WORK/dnsfilter.apk
CONF_OUT=$WORK/dnsfilter.conf
RAW=$WORK/hagezi.txt
LIST=$WORK/blocklist.txt
DIR=/sdcard/Android/data/$PKG/files/PersonalDNSFilter
MEDIA=/sdcard/Android/media/$PKG

GW=$(gateway)
[[ -n $GW ]] || die "could not read the default gateway from 'ip route'"

step "plan"
note "filter         personalDNSfilter, runs as a local VPN on the TV"
note "gateway        $GW (put inside the tunnel so apps cannot bypass the filter)"
# Every other DNS server the TV knows goes inside the tunnel too. A router that hands out an
# IPv6 DNS address (eero does) otherwise lets lookups go around the filter over IPv6.
# Only Fire OS 6 (Android 7.1) fills in net.dns*; on Fire OS 7 and 8 this finds nothing.
ROUTES=$GW
for prop in net.dns1 net.dns2 net.dns3 net.dns4; do
  ip=$(dev "getprop $prop" 2>/dev/null | tr -d '\r' || true)
  [[ $ip =~ ^[0-9a-fA-F.:]+$ && $ip == *[.:]* && $ip != 127.* && $ip != ::1 ]] || continue
  [[ "; $ROUTES;" == *"; $ip;"* ]] || ROUTES="$ROUTES; $ip"
done
[[ $ROUTES == "$GW" ]] || note "also routed    ${ROUTES#"$GW"; }"
note "list           HaGeZi Amazon trackers + data/blocklists/extra-hosts.txt"
upstream=$(fallback_dns) || exit 1
if [[ -n $upstream ]]; then
  note "upstream       $DNS_UPSTREAM (port 53, UDP)"
else
  note "upstream       the router"
fi
if [[ $PRIME == 1 ]]; then
  note "prime video    kept: its video CDN and push service stay out of the list"
else
  note "prime video    blocked (aiv-delivery, aiv-cdn, device messaging); YouTube is unaffected"
fi
if [[ $ALEXA == 1 ]]; then
  note "alexa          kept: its voice servers stay out of the list"
else
  note "alexa          blocked (data/blocklists/alexa-hosts.txt)"
fi
if [[ $DRY_RUN == 0 ]]; then confirm "Install the on-device DNS filter on $SERIAL?" || die "cancelled"; fi

step "blocklist"
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] download, compose and push a blocklist"
else
  fetch "$RAW" "$HAGEZI_URL" || die "could not download the tracker list"
  check_feed "$RAW" || die "the tracker list from $HAGEZI_URL does not look right, so it was not pushed; try again later"
  compose_blocklist "$RAW" > "$LIST"
  note "$(grep -c . "$LIST") entries"
fi

step "app"
app_version() { dev "dumpsys package $PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1; }
if [[ $DRY_RUN == 0 && $(app_version) == "$APK_VERSION" ]]; then
  # Reinstalling replaces the running always-on VPN, which drops a network adb session
  # mid-install. The pinned version is already there, so there is nothing to gain.
  note "personalDNSfilter $APK_VERSION already installed"
elif [[ $DRY_RUN == 0 ]]; then
  fetch "$APK" "$APK_URL" || die "could not download $APK_URL"
  if command -v sha256sum >/dev/null; then got=$(sha256sum "$APK" | awk '{print $1}')
  else got=$(shasum -a 256 "$APK" | awk '{print $1}'); fi
  [[ $got == "$APK_SHA256" ]] || die "checksum mismatch for $APK_URL (got $got) — not installing"
  note "checksum ok"
  out=$("$ADB" -s "$SERIAL" install -r "$APK" 2>&1 | tail -1)
  printf '  %s\n' "$out"
  # Replacing an app that holds the VPN can cut adb off before it prints Success, so the
  # version the TV reports afterwards is the real answer.
  adb_reconnect
  [[ $(app_version) == "$APK_VERSION" ]] || die "adb install failed: $out"
fi

# restore.sh disables the app; setting it up again turns it back on.
acmd pm enable --user 0 $PKG >/dev/null 2>&1
# The VPN consent dialog renders phone-sized on TVs and often ignores the
# remote. Pre-granting the app-op skips it entirely.
acmd appops set $PKG ACTIVATE_VPN allow >/dev/null
acmd dumpsys deviceidle whitelist +$PKG >/dev/null

step "config"
if [[ $DRY_RUN == 0 ]]; then
  fetch "$CONF_OUT" "$CONF_URL" || die "could not download the upstream config"
  # No filterAutoUpdateURL: the list is pushed by this script, so the TV never
  # downloads a blocklist and cannot end up with a half-written one.
  # previousAutoUpdateURL must match the (now empty) update URL: the template ships "<none>",
  # and a mismatch makes the app "update" at start and write an empty list over the pushed one.
  if [[ -n $upstream ]]; then
    fb=$upstream
  else
    fb="\\#router; [$GW]::53::UDP"
  fi
  sed -i.dumbtv \
    -e 's|^filterAutoUpdateURL = |#filterAutoUpdateURL = |' \
    -e 's|^previousAutoUpdateURL = .*|previousAutoUpdateURL = |' \
    -e 's|^detectDNS = .*|detectDNS = false|' \
    -e "s|^fallbackDNS = .*|fallbackDNS = $fb|" \
    -e "s|^routeIPs =.*|routeIPs = $ROUTES|" \
    -e 's|^routeUnderlyingDNS = .*|routeUnderlyingDNS = true|' \
    -e 's|^showInitialInfoPopUp = .*|showInitialInfoPopUp = false|' \
    -e 's|^reloadIntervalDays = .*|reloadIntervalDays = 3650|' \
    -e 's|^filterHostsFile = .*|filterHostsFile = FILTERHOSTS.TXT|' \
    "$CONF_OUT"
  # Fire OS 6 sends its time sync over the active network, which is this DNS-only
  # VPN. Whitelisting "android" lets its clock reach NTP, but it unfilters every
  # package running as the system user (on Fire OS 7 that includes Amazon's
  # protected logmanager). Fire OS 7/8 keep their clock without it, so it is honoured
  # on Fire OS 6 only. Opt-in: SYSTEM_DNS=1.
  if [[ ${SYSTEM_DNS:-0} == 1 && $SDK -ge 28 ]]; then
    note "SYSTEM_DNS=1 only applies to Fire OS 6; $(fireos_name) keeps its clock without it, so it stays off"
    SYSTEM_DNS=0
  fi
  if [[ ${SYSTEM_DNS:-0} == 1 ]]; then
    sed -i.dumbtv2 's|^androidAppWhiteList = .*|androidAppWhiteList = android|' "$CONF_OUT"
  else
    sed -i.dumbtv2 's|^androidAppWhiteList = .*|androidAppWhiteList = <none>|' "$CONF_OUT"
  fi
  for want in '^#filterAutoUpdateURL' '^detectDNS = false' '^routeUnderlyingDNS = true' '^previousAutoUpdateURL = $'; do
    grep -Eq "$want" "$CONF_OUT" || die "config template changed upstream (missing: $want) — update scripts/dnsfilter.sh"
  done
  if [[ ${SYSTEM_DNS:-0} == 1 ]]; then
    grep -Eq '^androidAppWhiteList = android' "$CONF_OUT" ||
      die "config template changed upstream (androidAppWhiteList did not take)"
  else
    grep -Eq '^androidAppWhiteList = <none>' "$CONF_OUT" ||
      die "config template changed upstream (androidAppWhiteList did not take)"
  fi
  grep -Fqx "routeIPs = $ROUTES" "$CONF_OUT" ||
    die "config template changed upstream (routeIPs did not take) — update scripts/dnsfilter.sh"
  if [[ -n $upstream ]]; then
    grep -Fqx "fallbackDNS = $fb" "$CONF_OUT" ||
      die "config template changed upstream (fallbackDNS did not take) — update scripts/dnsfilter.sh"
  fi
  # Fire OS 6 forgets always_on_vpn_app at boot, so there the app's own boot start is the
  # only thing that brings the filter up. Opt-out for TVs where both starts race (issue #58).
  if [[ $FILTER_AUTOSTART == 0 && $SDK -lt 28 ]]; then
    note "FILTER_AUTOSTART=0 is ignored on $(fireos_name): it needs the app's own boot start"
  elif [[ $FILTER_AUTOSTART == 0 ]]; then
    sed -i.dumbtv3 's|^AUTOSTART = .*|AUTOSTART = false|' "$CONF_OUT"
    grep -Fqx 'AUTOSTART = false' "$CONF_OUT" ||
      die "config template changed upstream (AUTOSTART did not take) — update scripts/dnsfilter.sh"
    note "AUTOSTART = false: always-on VPN alone starts the filter"
  fi
  note "patched; no list downloads on the TV"
fi

# Android 11 hides Android/data from adb, so the config and list cannot be pushed into the
# app's folder. An adb restore carries them in instead (scripts/appdata.sh): Android clears
# the app's data and unpacks the files as the app.
restore_into_app() {
  local data=$WORK/appdata/PersonalDNSFilter
  if ! backup_active; then
    note "this TV's backup service is off, so adb restore cannot carry the config in"
    return 1
  fi
  mkdir -p "$data"
  cp "$CONF_OUT" "$data/dnsfilter.conf"
  cp "$LIST" "$data/FILTERHOSTS.TXT"
  cp "$WORK/dld_cnt.txt" "$data/FILTERHOSTS.TXT.DLD_CNT"
  : > "$data/IDX_OUTDATED"
  # Without VERSION.TXT the app takes the files for an upgrade and merges its defaults back
  # in, its list downloads included.
  printf '%s' "$APK_VERSION" > "$data/VERSION.TXT"
  app_data_archive "$WORK/appdata.ab" "$PKG" "$APK_VERSION" "$SDK" \
    "$(list_of "$DATA/personaldnsfilter-signer.txt")" "$data" PersonalDNSFilter || return 1
  # With the filter's VPN up, the restore broke off part-way (an I/O error on the restore
  # stream, or a quiet stop after a few files); with the app stopped first it went through.
  # It stays stopped until the reboot that ends this script, as on the Fire OS 7 path below.
  dev "am force-stop $PKG" >/dev/null 2>&1
  sleep 3
  adb_reconnect
  note "the TV asks to confirm a restore of personalDNSfilter's data; this script answers it"
  restore_app_data "$WORK/appdata.ab"
}

# Android/media is the one place adb can write that the app can read. The list goes there and
# the app loads it from a file:// URL.
push_to_media() {
  dev "mkdir -p $MEDIA; rm -f $MEDIA/dumbtv-blocklist*.txt" >/dev/null 2>&1
  # Without these the app's file import writes an empty list and says nothing.
  dev "pm grant $PKG android.permission.READ_EXTERNAL_STORAGE" >/dev/null 2>&1
  dev "pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$LIST" "$MEDIA/$1" | tail -1
  [[ $(dev1 "grep -c . $MEDIA/$1") == "$(grep -c . "$LIST")" ]]
}

# When adb restore is not available: the settings are typed into the app's own config editor
# (Advanced settings > Edit configuration file). Pressing BACK saves it, merged over the app's
# defaults with the last copy of a key winning, so only the changed keys are typed, after the
# text already there. A list file with a new name gives the app a new URL, which it loads
# when its RESTART is pressed.
configure_in_app() {
  local name entries line i nodes lines=()
  name=dumbtv-blocklist-$(date +%s).txt
  push_to_media "$name" || { note "could not put the list in $MEDIA"; return 1; }
  entries=$(grep -c . "$LIST")
  lines=("detectDNS = false" "fallbackDNS = ${upstream:-[$GW]::53::UDP}" "routeIPs = $ROUTES"
    "routeUnderlyingDNS = true" "reloadIntervalDays = 3650" "filterHostsFile = FILTERHOSTS.TXT"
    "filterAutoUpdateURL = file:///storage/emulated/0/Android/media/$PKG/$name"
    "filterAutoUpdateURL_IDs = dumbtv" "filterAutoUpdateURL_categories = dumbtv"
    "filterAutoUpdateURL_switchs = true")
  [[ $FILTER_AUTOSTART == 0 ]] && lines+=("AUTOSTART = false")
  note "typing the settings into personalDNSfilter's config editor; leave the remote alone (about two minutes)"
  # Right after a first start the app's screen can stay unreadable to uiautomator ("null
  # root node") until another window has been in front; HOME and a new start clear it.
  for i in 1 2 3; do
    [[ $i -gt 1 ]] && dev input keyevent KEYCODE_HOME >/dev/null && sleep 2
    dev "am start -n $PKG/.DNSProxyActivity" >/dev/null 2>&1
    sleep 3
    nodes=$(ui_nodes) || continue
    [[ $nodes == *':id/advancedConfigCheck"'* || $nodes == *':id/closeInfoPopupBtn"'* ]] && break
  done
  # A fresh install opens the app's info dialog first, and it holds the focus until closed.
  if [[ -n $(ui_node closeInfoPopupBtn) ]]; then
    ui_tick disableInfoPopUp
    ui_press closeInfoPopupBtn
    sleep 2
  fi
  ui_tick advancedConfigCheck && ui_tick manuallyEditConfChk && ui_focus manuallyEditField || {
    note "could not reach the app's config editor"
    return 1
  }
  # PAGE_DOWN moves the cursor without leaving the field, as DPAD_DOWN does on the last line.
  dev input keyevent $(printf 'KEYCODE_PAGE_DOWN %.0s' $(seq 1 40)) KEYCODE_MOVE_END KEYCODE_ENTER >/dev/null
  for line in "${lines[@]}"; do
    ui_type "$line"
    dev input keyevent KEYCODE_ENTER >/dev/null
  done
  dev input keyevent KEYCODE_BACK >/dev/null
  sleep 2
  ui_press startBtn || { note "could not press the app's RESTART"; return 1; }
  for i in $(seq 1 30); do
    [[ $(ui_node logOutput) == *"Building index for $entries entries"* ]] && return 0
    sleep 2
  done
  note "the app did not load the list after RESTART"
  return 1
}

# The last fallback: the app is pointed at the list from its own screen, by a person.
manual_steps() {
  push_to_media dumbtv-blocklist.txt || die "push to $MEDIA failed"
  acmd settings put secure always_on_vpn_app $PKG >/dev/null
  step "one manual step ($(fireos_name) hides the app's files from adb)"
  note "the blocklist is on the TV; point personalDNSfilter at it once:"
  note "  1. open personalDNSfilter, tick Advanced settings, then tick Configure filter update"
  note "  2. untick every list in the table"
  note "  3. in the last row, named <new>, press the edit button at its right end"
  note "  4. URL: file:///storage/emulated/0/Android/media/$PKG/dumbtv-blocklist.txt (three slashes),"
  note "     tick Active, press OK"
  note "  5. reboot the TV, then run ./scripts/verify.sh: the Amazon hosts should answer 127.0.0.1"
  note "the app draws a phone-sized screen that some remotes cannot move through; docs/TROUBLESHOOTING.md"
  note "shows how to drive it over adb. DNS_UPSTREAM and the routing settings do not reach the app this way."
}

step "push"
restored=0
if [[ $DRY_RUN == 0 ]]; then
  # The first launch creates DIR and writes a default config; the app is stopped
  # afterwards so it cannot write that default over the config we push next.
  # Being stopped here is harmless because the reboot below is what reloads it.
  dev "am start -n $PKG/.DNSProxyActivity" >/dev/null 2>&1
  for i in 1 2 3 4 5; do
    dev "test -d $DIR" >/dev/null 2>&1 && break
    sleep 4
  done
  # Without a download-info file, the first boot after setup sees no
  # FILTERHOSTS.TXT.DLD_CNT and schedules an immediate "update ASAP" from the
  # (empty) update-URL list, replacing the pushed blocklist with a stub.
  # Writing it here marks the pushed list as fresh. Format mirrors
  # DNSFilterManager.writeDownloadInfoFile: "<entries>\n<millis>\n".
  # %s000 avoids %N, which macOS date(1) lacks.
  printf '%s\n%s000\n' "$(grep -c . "$LIST")" "$(date +%s)" > "$WORK/dld_cnt.txt"
  if ! dev "test -d $DIR" >/dev/null 2>&1 && [[ $SDK -ge 30 ]]; then
    if restore_into_app; then
      restored=1
      note "config and list are in the app's folder"
      # The restore cleared the app's data and stopped the app. The VPN consent and the
      # Doze exemption are kept outside that data; setting them again costs nothing.
      acmd appops set $PKG ACTIVATE_VPN allow >/dev/null
      acmd dumpsys deviceidle whitelist +$PKG >/dev/null
    elif configure_in_app; then
      restored=1
      note "the app loaded the list ($(grep -c . "$LIST") entries) from $MEDIA"
      dev input keyevent KEYCODE_HOME >/dev/null
    else
      note "the restore did not go through"
      manual_steps
      exit 0
    fi
  fi
fi
if [[ $DRY_RUN == 0 && $restored == 0 ]]; then
  dev "test -d $DIR" >/dev/null 2>&1 || die "the app did not create $DIR — is it installed?"
  dev "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  "$ADB" -s "$SERIAL" push "$CONF_OUT" "$DIR/dnsfilter.conf" | tail -1 || die "push of dnsfilter.conf failed"
  # A stale index makes the app keep serving the previous list. The marker must exist:
  # the app's rebuild ends by deleting IDX_OUTDATED and throws if it is not there.
  dev "rm -rf $DIR/FILTERHOSTS.TXT.idx; touch $DIR/IDX_OUTDATED" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$LIST" "$DIR/FILTERHOSTS.TXT" | tail -1 || die "push of FILTERHOSTS.TXT failed"
  "$ADB" -s "$SERIAL" push "$WORK/dld_cnt.txt" "$DIR/FILTERHOSTS.TXT.DLD_CNT" | tail -1 \
    || die "push of FILTERHOSTS.TXT.DLD_CNT failed"
fi

acmd settings put secure always_on_vpn_app $PKG >/dev/null

if [[ $SDK -lt 28 && $DRY_RUN == 0 ]]; then
  # Fire OS 6 drops always_on_vpn_app at boot, so the app's own boot receiver (AUTOSTART = true)
  # has to start it, and Android 7.1 sends no BOOT_COMPLETED to an app left force-stopped.
  # Opening it once clears that state; it starts filtering on the pushed list right away.
  dev "am start -n $PKG/.DNSProxyActivity" >/dev/null 2>&1
  sleep 8
  dev "input keyevent KEYCODE_HOME" >/dev/null 2>&1
fi

if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] reboot, then check that blocked hosts answer 0.0.0.0"
  exit 0
fi

# If the TV refuses `svc power reboot`, the plain reboot skips saving state, so save the VPN app-op first
acmd appops write-settings >/dev/null
sleep 3
step "reboot and wait"
reboot_wait
if [[ $NO_REBOOT == 1 ]]; then
  note "self-test skipped: reboot the TV, then run ./scripts/verify.sh"
  exit 0
fi
sleep 20

step "self-test"
fail=0
# The VPN takes a moment to come up after boot; give it three chances before
# calling it broken.
line=""
for attempt in 1 2 3; do
  line=$(dev "ping -c1 -W2 softwareupdates.amazon.com" | head -1)
  case $line in
    *0.0.0.0*|*127.0.0.1*) break ;;
    *) [[ $attempt -lt 3 ]] && sleep 10 ;;
  esac
done
case $line in
  *0.0.0.0*|*127.0.0.1*) ;;
  *) printf '  %-34s NOT BLOCKED after 30s: %s\n' softwareupdates.amazon.com "$line"; fail=1 ;;
esac
while read -r h; do
  line=$(dev "ping -c1 -W2 $h" | head -1)
  case $line in
    *0.0.0.0*|*127.0.0.1*) printf '  %-34s blocked\n' "$h" ;;
    *) printf '  %-34s NOT BLOCKED: %s\n' "$h" "$line"; fail=1 ;;
  esac
done < <(expected_blocked)

line=$(dev "ping -c1 -W2 www.youtube.com" | head -1)
case $line in
  *0.0.0.0*|*127.0.0.1*) printf '  %-34s WRONGLY BLOCKED\n' www.youtube.com; fail=1 ;;
  *) printf '  %-34s allowed\n' www.youtube.com ;;
esac

if [[ $fail == 1 ]]; then
  note "the filter is not in the DNS path. Do not force-stop the app — reboot the TV,"
  note "then run ./scripts/verify.sh. See docs/TROUBLESHOOTING.md."
  if [[ $(dev1 settings get secure always_on_vpn_app) == "$PKG" ]] &&
     dev 'ip addr show' | grep -Eq '^[0-9]+: tun[0-9]+:.*UP'; then
    note "the tunnel is up and always-on is set, yet DNS goes around it: if a reboot does not"
    note "fix it, add FILTER_AUTOSTART=0 to dumbtv.conf and run this script again"
  fi
  if [[ $restored == 1 ]]; then
    note "the config went in without adb push; if a reboot does not fix it, see \"Fire OS 8\" in docs/TROUBLESHOOTING.md"
  fi
  exit 1
fi
note "filter is live"
