#!/usr/bin/env bash
# Builds the blocklist on this machine, pushes it to the TV and installs the
# filter that uses it. No root, no router access, no account. Ends with a reboot
# and a self-test. Usage: ./scripts/dnsfilter.sh [--dry-run] [-y]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
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

GW=$(gateway)
[[ -n $GW ]] || die "could not read the default gateway from 'ip route'"

step "plan"
note "filter         personalDNSfilter, runs as a local VPN on the TV"
note "gateway        $GW (put inside the tunnel so apps cannot bypass the filter)"
note "list           HaGeZi Amazon trackers + data/blocklists/extra-hosts.txt"
if [[ -n ${DNS_UPSTREAM:-} ]]; then
  note "upstream       $DNS_UPSTREAM"
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
  # DNS_UPSTREAM replaces the router. Empty keeps the same router line as before.
  fb=$(fallback_dns) || exit 1
  fb=${fb%$'\r'}
  if [[ -z $fb ]]; then
    fb="\\#router; [$GW]::53::UDP"
  fi
  sed -i.dumbtv \
    -e 's|^filterAutoUpdateURL = |#filterAutoUpdateURL = |' \
    -e 's|^previousAutoUpdateURL = .*|previousAutoUpdateURL = |' \
    -e 's|^detectDNS = .*|detectDNS = false|' \
    -e "s|^fallbackDNS = .*|fallbackDNS = $fb|" \
    -e "s|^routeIPs =.*|routeIPs = $GW|" \
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
  grep -Fqx "routeIPs = $GW" "$CONF_OUT" ||
    die "config template changed upstream (routeIPs did not take) — update scripts/dnsfilter.sh"
  if [[ -n ${DNS_UPSTREAM:-} ]]; then
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

step "push"
if [[ $DRY_RUN == 0 ]]; then
  # The first launch creates DIR and writes a default config; the app is stopped
  # afterwards so it cannot write that default over the config we push next.
  # Being stopped here is harmless because the reboot below is what reloads it.
  dev "am start -n $PKG/.DNSProxyActivity" >/dev/null 2>&1
  for i in 1 2 3 4 5; do
    dev "test -d $DIR" >/dev/null 2>&1 && break
    sleep 4
  done
  if ! dev "test -d $DIR" >/dev/null 2>&1 && [[ $SDK -ge 30 ]]; then
    # Android 11 hides Android/data from adb, so neither the config nor the list can be
    # pushed there. Android/media is still writable, and the app can import a file:// list
    # from it. Choosing that list is a setting only the app's own screen can change.
    MEDIA=/sdcard/Android/media/$PKG
    dev "mkdir -p $MEDIA" >/dev/null 2>&1
    # Without these the app's file import writes an empty list and says nothing.
    dev "pm grant $PKG android.permission.READ_EXTERNAL_STORAGE" >/dev/null 2>&1
    dev "pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" push "$LIST" "$MEDIA/dumbtv-blocklist.txt" | tail -1 || die "push to $MEDIA failed"
    acmd settings put secure always_on_vpn_app $PKG >/dev/null
    step "one manual step ($(fireos_name) hides the app's files from adb)"
    note "the blocklist is on the TV; tell the app to use it, with the remote:"
    note "  1. open personalDNSfilter, then Advanced settings > Configure filter update"
    note "  2. add an entry: file:///storage/emulated/0/Android/media/$PKG/dumbtv-blocklist.txt"
    note "  3. untick every other entry, go back, and reboot the TV"
    note "  4. run ./scripts/verify.sh: the Amazon hosts should answer 127.0.0.1"
    note "the routing tweaks (detectDNS=false, routeIPs) cannot be applied this way; see docs/TROUBLESHOOTING.md"
    exit 0
  fi
  dev "test -d $DIR" >/dev/null 2>&1 || die "the app did not create $DIR — is it installed?"
  dev "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  "$ADB" -s "$SERIAL" push "$CONF_OUT" "$DIR/dnsfilter.conf" | tail -1 || die "push of dnsfilter.conf failed"
  # A stale index makes the app keep serving the previous list. The marker must exist:
  # the app's rebuild ends by deleting IDX_OUTDATED and throws if it is not there.
  dev "rm -rf $DIR/FILTERHOSTS.TXT.idx; touch $DIR/IDX_OUTDATED" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$LIST" "$DIR/FILTERHOSTS.TXT" | tail -1 || die "push of FILTERHOSTS.TXT failed"
  # Without a download-info file, the first boot after setup sees no
  # FILTERHOSTS.TXT.DLD_CNT and schedules an immediate "update ASAP" from the
  # (empty) update-URL list, replacing the pushed blocklist with a stub.
  # Writing it here marks the pushed list as fresh. Format mirrors
  # DNSFilterManager.writeDownloadInfoFile: "<entries>\n<millis>\n".
  # %s000 avoids %N, which macOS date(1) lacks.
  printf '%s\n%s000\n' "$(grep -c . "$LIST")" "$(date +%s)" > "$WORK/dld_cnt.txt"
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
  exit 1
fi
note "filter is live"
