#!/bin/bash
# Event-driven clean-up for the Fire TV AirPlay bug (airplay/README.md).
#
# Leaving AirPlay leaves the player's task behind. Low memory often kills the player process
# within seconds (seen 1.7 s after HOME); the Mac's automatic reconnect ~10 s later then lands
# on that dead task, the restarted player quits, and the Mac "shares into nothing" for 40 s.
#
# This watches the TV's event log and, the moment the player process dies, removes the
# AirPlay tasks it left. Rule: only while no player process is running, so a live session is
# never touched. Also sweeps once on every (re)connect, e.g. after this machine wakes.
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

while true; do
  if connected; then
    sweep "connect"
    # -T 1: start at the newest line, so old deaths are not replayed.
    "$ADB" -s "$TV" logcat -v brief -b events -T 1 2>/dev/null | while IFS= read -r line; do
      case $line in
        *am_proc_died*",$PLAYER,"*) sweep "player died" ;;
      esac
    done
  fi
  sleep 10
done
