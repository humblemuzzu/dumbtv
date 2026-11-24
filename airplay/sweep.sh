#!/bin/bash
# Event-driven clean-up for the Fire TV AirPlay bug (airplay/README.md).
#
# Leaving AirPlay leaves the player's task behind. Low memory often kills the player process
# within seconds (seen 1.7 s after HOME); the Mac's automatic reconnect ~10 s later then lands
# on that dead task, the restarted player quits, and the Mac "shares into nothing" for 40 s.
#
# In addition, low memory causes com.amazon.airplaydaemon to drop Bonjour adverts (_airplay._tcp.)
# while port 7000 RTSP stays open, leaving Apple clients unable to discover the TV.
#
# This watches the TV's event log and, the moment the player process dies, removes the
# AirPlay tasks it left. Rule: only while no player process is running, so a live session is
# never touched. Also sweeps once on every (re)connect, e.g. after this machine wakes.
# On macOS, it also ensures Bonjour advertising remains active while RTSP port 7000 is open.
# Runs forever under launchd (install.sh); safe on several machines at once.
set -u

TV=${ADB_SERIAL:?set ADB_SERIAL=<tv-ip>:5555}
ADB=${ADB:-adb}
LOG=${LOG:-$HOME/Library/Logs/dumbtv-airplay-sweeper.log}
PLAYER=com.apple.airplay.amazon.airplay

note() { printf '%s %s\n' "$(date '+%F %T')" "$*" >>"$LOG"; }
# macOS has no timeout(1); a TV that drops off the network must not hang a one-shot call.
run() { perl -e 'alarm shift; exec @ARGV' 15 "$ADB" -s "$TV" "$@"; }

connected() {
  [[ $(run get-state 2>/dev/null) == device ]] && return 0
  perl -e 'alarm 15; exec @ARGV' "$ADB" connect "$TV" >/dev/null 2>&1
  [[ $(run get-state 2>/dev/null) == device ]]
}

# Removes hidden AirPlay player tasks, but only when the player process is dead.
sweep() {
  local why=$1 list id
  if run shell pidof "$PLAYER" >/dev/null 2>&1; then return 0; fi
  list=$(run shell am stack list 2>/dev/null) || return 0
  for id in $(printf '%s\n' "$list" |
      sed -nE "s#^ *taskId=([0-9]+): ${PLAYER//./\\.}/[^ ]* .* visible=false( .*)?\$#\\1#p"); do
    run shell am stack remove "$id" >/dev/null 2>&1 && note "removed dead AirPlay task $id ($why)"
  done
}

BONJOUR_PID=
stop_bonjour() {
  if [[ -n ${BONJOUR_PID:-} ]] && kill -0 "$BONJOUR_PID" 2>/dev/null; then
    kill "$BONJOUR_PID" 2>/dev/null || true
    wait "$BONJOUR_PID" 2>/dev/null || true
    note "stopped Bonjour proxy (pid $BONJOUR_PID)"
    BONJOUR_PID=
  fi
}
trap 'stop_bonjour; exit 0' EXIT TERM INT

# On macOS, proxies Bonjour adverts if the TV's daemon drops mDNS while RTSP 7000 is listening.
check_bonjour() {
  command -v dns-sd >/dev/null 2>&1 || return 0
  local ip=${TV%:5555}
  if ! nc -z -G 2 "$ip" 7000 2>/dev/null; then
    stop_bonjour
    return 0
  fi
  if [[ -n ${BONJOUR_PID:-} ]] && kill -0 "$BONJOUR_PID" 2>/dev/null; then
    return 0
  fi
  local name
  name=$(run shell settings get global device_name 2>/dev/null | tr -d '\r')
  [[ -n $name ]] || name="Fire TV"
  if perl -e 'alarm 2; exec @ARGV' dns-sd -B _airplay._tcp local. 2>/dev/null | grep -q "$name"; then
    return 0
  fi
  local model mac serial host
  model=$(run shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  [[ -n $model ]] || model="AFTHY7ABBA"
  mac=$(run shell 'cat /sys/class/net/wlan0/address 2>/dev/null || cat /sys/class/net/eth0/address 2>/dev/null' | tr -d '\r' | tr '[:lower:]' '[:upper:]')
  [[ -n $mac ]] || mac="00:00:00:00:00:00"
  serial=$(run shell getprop ro.serialno 2>/dev/null | tr -d '\r')
  host="${name//[^a-zA-Z0-9-]/_}.local"

  dns-sd -P "$name" _airplay._tcp local. 7000 "$host" "$ip" \
    "manufacturer=Amazon" "model=$model" "deviceid=$mac" "pi=$mac" \
    "features=0x7F8AD0,0x38BCF46" "flags=0x244" "srcvers=377.40.00" "protovers=1.1" \
    "acl=0" "rsf=0x3" ${serial:+"serialNumber=$serial"} >/dev/null 2>&1 &
  BONJOUR_PID=$!
  note "started Bonjour proxy for $name at $ip:7000 (pid $BONJOUR_PID)"
}

while true; do
  if connected; then
    sweep "connect"
    check_bonjour
    # -T 1: start at the newest line, so old deaths are not replayed.
    "$ADB" -s "$TV" logcat -v brief -b events -T 1 2>/dev/null | while IFS= read -r line; do
      case $line in
        *am_proc_died*",$PLAYER,"*) sweep "player died" ;;
      esac
    done
  fi
  stop_bonjour
  sleep 10
done
