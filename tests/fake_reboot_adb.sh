#!/usr/bin/env bash
# A TV that restarts when asked, then refuses its first $REFUSALS connects: Fire OS 8 can come
# back with ADB debugging switched off until the owner turns it on again.
connects=$STATE/connects booted=$STATE/booted
n=$(cat "$connects" 2>/dev/null || echo 0)
case "$*" in
  "-s test-tv get-state") echo device ;;
  "connect test-tv")
    n=$((n + 1)); echo $n > "$connects"
    if [[ $n -le $REFUSALS ]]; then echo "failed to connect to 'test-tv:5555': Connection refused"; exit 1; fi
    echo "connected to test-tv" ;;
  "-s test-tv shell echo ok") echo ok ;;
  "-s test-tv shell cat /proc/sys/kernel/random/boot_id") [[ -f $booted ]] && echo boot-1 || echo boot-0 ;;
  "-s test-tv shell svc power reboot") touch "$booted" ;;
  "-s test-tv shell getprop sys.shutdown.requested") echo 1reboot ;;
  "-s test-tv shell getprop sys.boot_completed") [[ $n -gt $REFUSALS ]] && echo 1 ;;
  *) exit 92 ;;
esac
