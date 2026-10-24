#!/usr/bin/env bash
# Shared helpers. Sourced by the other scripts; not meant to be run directly.
# Must stay bash 3.2 compatible: that is what /usr/bin/env bash is on macOS.

ADB=${ADB:-adb}
# Git Bash (MSYS2) on Windows rewrites arguments that look like Unix paths, so
# `adb push x /sdcard/...` would target "C:/Program Files/Git/sdcard/...". Device paths
# must reach adb unchanged. Other shells ignore this variable.
export MSYS2_ARG_CONV_EXCL="${MSYS2_ARG_CONV_EXCL:+$MSYS2_ARG_CONV_EXCL;}/sdcard;/data;/storage;/system"
DRY_RUN=${DRY_RUN:-0}
ASSUME_YES=${ASSUME_YES:-0}
NO_REBOOT=${NO_REBOOT:-0}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DATA="$ROOT/data"
REPORT=${REPORT:-$ROOT/report}
BOOT_SECONDS=${BOOT_SECONDS:-45}
CONF=${CONF:-$ROOT/dumbtv.conf}
# Both names are accepted; ADB_SERIAL is what the docs tell people to set.
SERIAL=${SERIAL:-${ADB_SERIAL:-}}
# Android API level of the TV, read by need_device. 25 = Fire OS 6, 28 = Fire OS 7, 30 = Fire OS 8.
SDK=${SDK:-0}
# 1 when the TV has `cmd package install-existing`, i.e. an uninstalled system app can come back.
REINSTALL=${REINSTALL:-0}

# Preferences written by scripts/configure.sh. Absent file = defaults: remove no
# app, keep Prime Video and Alexa, PRIME=1, ALEXA=1, LAUNCHER=1, DNSFILTER=1.
load_conf() {
  KEEP_APPS=${KEEP_APPS:-}
  REMOVE_EXTRA=${REMOVE_EXTRA:-}
  PRIME=${PRIME:-1}
  ALEXA=${ALEXA:-1}
  LAUNCHER=${LAUNCHER:-1}
  DNSFILTER=${DNSFILTER:-1}
  SYSTEM_DNS=${SYSTEM_DNS:-0}
  FILTER_AUTOSTART=${FILTER_AUTOSTART:-1}
  DNS_UPSTREAM=${DNS_UPSTREAM:-}
  [[ -f $CONF ]] && source "$CONF"
  KEEP_APPS=${KEEP_APPS:-}; REMOVE_EXTRA=${REMOVE_EXTRA:-}
  PRIME=${PRIME:-1}; ALEXA=${ALEXA:-1}; LAUNCHER=${LAUNCHER:-1}; DNSFILTER=${DNSFILTER:-1}
  SYSTEM_DNS=${SYSTEM_DNS:-0}
  FILTER_AUTOSTART=${FILTER_AUTOSTART:-1}
  DNS_UPSTREAM=${DNS_UPSTREAM:-}
}

# What debloat.sh disables: the ads and tracking list, plus Alexa only with ALEXA=0.
cleanup_list() {
  list_of "$(fos packages.txt)" | awk '{print $1}'
  if [[ $ALEXA == 0 ]]; then list_of "$(fos alexa.txt)" | awk '{print $1}'; fi
}

# The DNS filter's list: the downloaded tracker feed $1 plus this repo's hosts.
# Prime Video and Alexa need some of those hosts, so the owner's answers decide.
compose_blocklist() {
  {
    grep -v '^#' "$1"
    list_of "$DATA/blocklists/extra-hosts.txt"
    if [[ $ALEXA == 0 ]]; then list_of "$DATA/blocklists/alexa-hosts.txt"; fi
  } | awk 'NF' | if [[ $PRIME == 1 ]]; then
    grep -viE 'aiv-delivery|aiv-cdn|^device-messaging-na\.amazon\.com$' || true
  else cat; fi
}

# Hosts verify.sh and the filter's self-test expect to be blocked.
expected_blocked() {
  list_of "$DATA/blocklists/verify-blocked.txt"
  if [[ $ALEXA == 0 ]]; then echo api.amazonalexa.com; fi
  if [[ $PRIME == 0 ]]; then echo device-messaging-na.amazon.com; fi
}

keep_pkg() {
  local k
  for k in $KEEP_APPS; do [[ $k == "$1" ]] && return 0; done
  return 1
}

# Refuse to run when a keep entry matches nothing: a typo would otherwise remove
# the app the user asked to keep.
check_keep_list() {
  local k known
  [[ -z $KEEP_APPS ]] && return 0
  known=$(list_of "$(fos packages.txt)" | awk '{print $1}')
  known="$known $(list_of "$(fos alexa.txt)" | awk '{print $1}')"
  known="$known $(list_of "$(fos user-apps.txt)" | awk '{print $1}') $REMOVE_EXTRA"
  for k in $KEEP_APPS; do
    printf '%s\n' "$known" | tr ' ' '\n' | grep -qx "$k" ||
      die "keep list entry '$k' matches nothing in the removal lists (typo?)"
  done
}

step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*" >&2; }
ask_note() { printf '  %s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

dev()  { "$ADB" -s "$SERIAL" shell "$@" 2>&1 </dev/null; }
dev1() { dev "$@" | head -1 | tr -d '\r'; }

# A dropped connection must not look like "package absent".
dev_ok() {
  local out
  out=$(dev "$@") || return 1
  printf '%s\n' "$out" | grep -qiE "^error: (device|closed|no devices)|device '[^']*' not found" &&
    die "device $SERIAL stopped responding — reconnect and re-run"
  printf '%s\n' "$out"
}

# Device command that honours --dry-run. The dry-run line goes to stderr so a
# caller's >/dev/null cannot hide it.
acmd() {
  if [[ $DRY_RUN == 1 ]]; then note "[dry-run] $*"; return 0; fi
  dev "$@"
}

confirm() {
  [[ $ASSUME_YES == 1 ]] && return 0
  printf '%s [y/N] ' "$1" >&2
  read -r reply
  case $reply in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

parse_flags() {
  for a in "$@"; do
    case $a in
      --dry-run) DRY_RUN=1 ;;
      -y|--yes)  ASSUME_YES=1 ;;
      --no-reboot) NO_REBOOT=1 ;;
      *) die "unknown flag: $a" ;;
    esac
  done
}

need_device() {
  command -v "$ADB" >/dev/null || die "adb not found in PATH"
  "$ADB" start-server >/dev/null 2>&1
  if [[ -z $SERIAL ]]; then
    local found count
    found=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
    [[ -z $found ]] && die "no adb device. Enable ADB debugging on the TV, then: adb connect <tv-ip>:5555"
    count=$(printf '%s\n' "$found" | wc -l | tr -d ' ')
    [[ $count -gt 1 ]] && die "several devices attached; set ADB_SERIAL to the one you mean: $(printf '%s' "$found" | tr '\n' ' ')"
    SERIAL=$(printf '%s\n' "$found" | head -1)
  fi
  dev getprop ro.build.version.sdk >/dev/null || die "device $SERIAL is not responding"
  SDK=$(dev1 getprop ro.build.version.sdk | tr -d '\r')
  case $SDK in ''|*[!0-9]*) SDK=0 ;; esac
  grep -q install-existing <<<"$(dev 'cmd package help')" && REINSTALL=1 || REINSTALL=0
}

# Data file for this Fire OS generation. Fire OS 6 (Android 7.1) has its own lists in
# data/fireos6/; a file missing there falls back to the Fire OS 7/8 one in data/fireos/.
# Before need_device has run (SDK=0) this is always the Fire OS 7/8 file.
fos() {
  if [[ $SDK -gt 0 && $SDK -lt 28 && -f $DATA/fireos6/$1 ]]; then printf '%s' "$DATA/fireos6/$1"
  else printf '%s' "$DATA/fireos/$1"; fi
}

fireos_name() {
  if [[ $SDK -gt 30 ]]; then echo "Fire OS newer than 8 (sdk $SDK, untested)"
  elif [[ $SDK -ge 30 ]]; then echo "Fire OS 8"
  elif [[ $SDK -ge 28 ]]; then echo "Fire OS 7"
  elif [[ $SDK -ge 25 ]]; then echo "Fire OS 6"
  else echo "Fire OS 5 or older (unsupported)"; fi
}

# What personalDNSfilter asks for a name that is not on the local list.
# DNS_UPSTREAM is a space-separated list of resolver IPs. Empty keeps the router,
# which is the default: a second blocklist at the resolver also sinks hosts the
# local list deliberately left alone.
fallback_dns() {
  local ip item out=""
  if [[ -z ${DNS_UPSTREAM:-} ]]; then
    return 0
  fi
  for ip in $DNS_UPSTREAM; do
    case $ip in
      *[!0-9A-Fa-f:.]*) die "DNS_UPSTREAM entry '$ip' is not an IP address" ;;
    esac
    case $ip in
      *:*)
        case $ip in *.*) die "DNS_UPSTREAM entry '$ip' is not an IP address" ;; esac
        item="[$ip]::53::UDP"
        ;;
      *.*.*.*) item="$ip::53::UDP" ;;
      *) die "DNS_UPSTREAM entry '$ip' is not an IP address" ;;
    esac
    if [[ -z $out ]]; then out=$item; else out="$out; $item"; fi
  done
  printf '%s\n' "$out"
}

# The package lists and app-op calls are written for Fire OS 6 and later.
need_supported_fireos() {
  is_fireos || die "this is for Amazon Fire OS; see docs/OTHER-TVS.md"
  [[ $SDK -ge 25 ]] || die "$(fireos_name): Fire OS 5 and older refuse pm disable-user, so nothing here can work; see docs/OTHER-TVS.md"
}

# Background execution off/on. RUN_ANY_IN_BACKGROUND only exists from Android 9; on
# Fire OS 6 appops rejects it, so only RUN_IN_BACKGROUND is set there.
bg_off() {
  acmd appops set "$1" RUN_IN_BACKGROUND ignore >/dev/null 2>&1
  [[ $SDK -ge 28 ]] && acmd appops set "$1" RUN_ANY_IN_BACKGROUND ignore >/dev/null 2>&1
  return 0
}
bg_on() {
  acmd appops set "$1" RUN_IN_BACKGROUND allow >/dev/null 2>&1
  [[ $SDK -ge 28 ]] && acmd appops set "$1" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1
  return 0
}

# Names of running processes. Fire OS 6 ships the old toolbox ps, which answers
# `ps -A -o NAME` with "bad pid '-A'"; its last column is the process name.
running_names() {
  local out
  out=$(dev 'ps -A -o NAME' | tr -d '\r')
  if printf '%s\n' "$out" | grep -q "bad pid"; then
    dev ps | tr -d '\r' | awk 'NR>1 {print $NF}'
  else
    printf '%s\n' "$out"
  fi
}

is_fireos()  { case $(dev1 getprop ro.product.manufacturer) in Amazon*) return 0 ;; *) return 1 ;; esac; }
gateway() {
  local gw
  gw=$(dev 'ip route show table all' \
    | awk '/^default/ && / via / && !/linkdown/ && !/ dev tun/ {
             for (i=1;i<=NF;i++) if ($i=="via") { print $(i+1); exit }
           }')
  [[ -z $gw ]] && gw=$(dev 'ip route show table all' \
    | awk '/^default/ && / via / && !/ dev tun/ {
             for (i=1;i<=NF;i++) if ($i=="via") { print $(i+1); exit }
           }')
  [[ -z $gw ]] && gw=$(dev 'dumpsys connectivity' | sed -n 's/.*ServerAddress: \([0-9.]*\).*/\1/p' | head -1)
  [[ -z $gw ]] && gw=$(dev 'getprop dhcp.wlan0.gateway' | tr -d '\r')
  printf '%s' "$gw"
}
uid_of()     { dev "dumpsys package $1" | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1; }
present()    { dev_ok "pm path $1" | grep -q '^package:'; }
# Shipped with the TV, as opposed to installed from a store.
is_system()  { dev_ok "pm list packages -s" | tr -d '\r' | grep -qx "package:$1"; }

reboot_wait() {
  if [[ ${NO_REBOOT:-0} == 1 ]]; then
    note "not rebooting (--no-reboot): reboot the TV before the next step"
    return 0
  fi
  note "rebooting the TV"
  adb_reconnect
  local i before retried=0
  before=$(boot_id)
  ask_reboot
  sleep "$BOOT_SECONDS"
  for i in $(seq 1 30); do
    "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
    if [[ $(dev1 getprop sys.boot_completed) == 1 ]]; then
      if [[ $(boot_id) != "$before" ]]; then
        note "booted"
        return 0
      fi
      [[ $retried == 1 ]] && die "the TV did not restart; restart it by hand, then run this again"
      retried=1
      note "the TV is still up from before; asking again"
      dev reboot >/dev/null 2>&1 || true
      sleep "$BOOT_SECONDS"
      continue
    fi
    sleep 5
  done
  die "device did not report boot_completed; check the TV by hand before running anything else"
}

boot_id() { dev1 cat /proc/sys/kernel/random/boot_id; }

# A plain `reboot` cuts the power without letting Android save its state, so a package
# enabled or disabled in the last few seconds comes back as it was. `svc power reboot`
# shuts down properly. It is killed part-way through the shutdown, so chaining a
# fallback after it with || would cut that shutdown short.
ask_reboot() { dev svc power reboot >/dev/null 2>&1 || true; }

# A network adb session can drop when the TV's network changes under it (an app that
# holds the VPN being replaced). Brings it back, or stops the run.
adb_reconnect() {
  local i
  for i in $(seq 1 12); do
    [[ $("$ADB" -s "$SERIAL" get-state 2>/dev/null) == device && $(dev1 echo ok) == ok ]] && return 0
    if [[ $SERIAL == *:* ]]; then
      "$ADB" disconnect "$SERIAL" >/dev/null 2>&1
      "$ADB" connect "$SERIAL" >/dev/null 2>&1
    fi
    sleep 3
  done
  die "lost adb to $SERIAL; check the TV is on, then run this again"
}

list_of() { sed 's/#.*//; s/[[:space:]]*$//' "$1" | awk 'NF'; }

# Downloads $2 to $1. GitHub's release CDN sometimes resets the first connection
# ("curl: (35) Recv failure: Connection reset by peer"), so a run failed and the
# re-run passed. Plain --retry skips connection resets; --retry-all-errors (curl
# 7.71+) retries them too. -f still fails on a 404 once the retries are spent.
fetch() { curl -fsSL --retry 4 --retry-delay 3 --retry-all-errors --connect-timeout 20 -o "$1" "$2"; }
# Compare on the first field: list entries carry trailing spaces after comments
# are stripped, and an exact match would silently fail to protect anything.
protected_by_policy() { list_of "$(fos never-disable.txt)" | awk '{print $1}' | grep -qx "$1"; }

# App name for a package, from data/apps.txt. Names may contain spaces, so match
# the package anywhere in the line and keep whatever precedes it.
app_name() {
  awk -v p="$1" 'index($0,p)==0 {next} {n=substr($0,1,index($0,p)-1); sub(/[[:space:]]+$/,"",n); print n; exit}' \
    "$DATA/apps.txt" 2>/dev/null
}
