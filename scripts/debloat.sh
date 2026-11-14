#!/usr/bin/env bash
# Disables Amazon's ads, tracking and telemetry packages and applies the privacy
# settings. Apps are left alone unless the owner named them in dumbtv.conf.
# Usage: ./scripts/debloat.sh [--dry-run] [-y] [--no-reboot]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
parse_flags "$@"
need_device
need_supported_fireos
load_conf
check_keep_list

settings_files() {
  list_of "$(fos settings.txt)"
  if [[ $ALEXA == 0 ]]; then list_of "$(fos settings.alexa.txt)"; fi
}

step "plan"
note "platform   $(fireos_name) (sdk $SDK), lists from ${DATA#$ROOT/}/$(basename "$(dirname "$(fos packages.txt)")")"
note "disable    $(cleanup_list | wc -l | tr -d ' ') Amazon ads, tracking and telemetry packages (reversible)"
if [[ $ALEXA == 1 ]]; then note "alexa      kept (ALEXA=1)"; else note "alexa      disabled (ALEXA=0)"; fi
if [[ -n $REMOVE_EXTRA ]]; then note "your apps  removing only what you named: $REMOVE_EXTRA"
else note "your apps  none removed"; fi
note "settings   $(settings_files | wc -l | tr -d ' ')"
if [[ $DRY_RUN == 0 ]]; then
  confirm "Disable Amazon's ads and tracking on $SERIAL${REMOVE_EXTRA:+ and remove the apps you named}?" || die "cancelled"
fi

# never-disable.txt packages that are off. Older lists disabled some of them: Prime Video's
# com.amazon.firebat until October 2026, the phone app's pairing screen until November. Nothing
# switched them back on, so Prime Video stayed gone after an update (#175).
needed_off() {
  local off
  off=$(dev 'pm list packages -d' | tr -d '\r' | sed -n 's/^package://p')
  list_of "$(fos never-disable.txt)" | awk '{print $1}' | grep -xF -f <(printf '%s\n' "$off") || true
}

if [[ $DRY_RUN == 1 ]]; then
  step "packages the TV needs (dry-run)"
  for p in $(needed_off); do note "$p  would be enabled again"; done
  step "Amazon packages (dry-run)"
  for p in $(cleanup_list); do
    protected_by_policy "$p" && { note "$p  SKIP (never-disable list)"; continue; }
    keep_pkg "$p" && { note "$p  SKIP (keep list)"; continue; }
    present "$p" && note "$p  would be disabled"
  done
  step "apps you named (dry-run)"
  for p in $REMOVE_EXTRA; do
    protected_by_policy "$p" && { note "$p  SKIP (never-disable list)"; continue; }
    present "$p" || continue
    if is_system "$p"; then note "$p  would be disabled"; else note "$p  would be uninstalled"; fi
  done
  note "dry-run: nothing was changed"
  exit 0
fi

mkdir -p "$REPORT"
log="$REPORT/debloat.txt"
: > "$log"

# Prints the outcome of `pm disable-user` for $1 and logs it.
disable_pkg() {
  local p=$1 out
  out=$(acmd pm disable-user --user 0 "$p" | head -1)
  case $out in
    *disabled-user*) printf '  %-46s disabled\n' "$p"; disabledn=$((disabledn+1))
                     printf '%s\tdisabled\n' "$p" >> "$log" ;;
    *protected*)     printf '  %-46s PROTECTED\n' "$p"; protectedn=$((protectedn+1))
                     printf '%s\tprotected\n' "$p" >> "$log" ;;
    # Fire OS ships some packages disabled by the system (state 2, e.g. com.android.nfc
    # on a Fire TV Stick 4K Max). The shell may not move a package out of that state,
    # and it is off already, so this is not a failure.
    *"Shell cannot change component state"*)
      if dev "dumpsys package $p" | grep -q 'User 0:.* enabled=2 '; then
                     printf '  %-46s already disabled by the system\n' "$p"; sysoff=$((sysoff+1))
                     printf '%s\tsystem-disabled\n' "$p" >> "$log"
      else
                     printf '  %-46s SUSPECT: %s\n' "$p" "$out"; suspect=$((suspect+1))
                     printf '%s\tsuspect\t%s\n' "$p" "$out" >> "$log"
      fi ;;
    *)               printf '  %-46s SUSPECT: %s\n' "$p" "$out"; suspect=$((suspect+1))
                     printf '%s\tsuspect\t%s\n' "$p" "$out" >> "$log" ;;
  esac
}

step "Amazon packages"
removed=0; disabledn=0; protectedn=0; sysoff=0; absent=0; suspect=0
for p in $(cleanup_list); do
  if protected_by_policy "$p"; then
    printf '  %-46s SKIP (never-disable list)\n' "$p"; continue
  fi
  if keep_pkg "$p"; then
    printf '  %-46s SKIP (keep list)\n' "$p"; continue
  fi
  if ! present "$p"; then
    printf '  %-46s absent\n' "$p"; absent=$((absent+1)); continue
  fi
  disable_pkg "$p"
done

step "packages the TV needs"
enabledn=0; needed=$(needed_off)
[[ -n $needed ]] || note "none of them is off"
for p in $needed; do
  out=$(acmd pm enable --user 0 "$p" | head -1)
  case $out in
    *"new state: enabled"*)
      printf '  %-46s enabled again (an older dumbtv had disabled it)\n' "$p"; enabledn=$((enabledn+1))
      printf '%s\tenabled-again\n' "$p" >> "$log" ;;
    # Shipped disabled by the system, which the shell may not change; left as Amazon set it.
    *) printf '  %-46s off, left as it is: %s\n' "$p" "$out" ;;
  esac
done

if [[ -n $REMOVE_EXTRA ]]; then
  step "apps you named"
  for p in $REMOVE_EXTRA; do
    if protected_by_policy "$p"; then
      printf '  %-46s SKIP (never-disable list)\n' "$p"; continue
    fi
    if ! present "$p"; then
      printf '  %-46s absent\n' "$p"; continue
    fi
    # The TV's own apps are disabled so restore.sh can bring them back; store apps
    # are uninstalled and come back from the store.
    if is_system "$p"; then
      disable_pkg "$p"
      continue
    fi
    out=$(acmd pm uninstall --user 0 "$p" | head -1)
    case $out in
      Success*) printf '  %-46s uninstalled\n' "$p"; removed=$((removed+1))
                printf '%s\tremoved\n' "$p" >> "$log" ;;
      *)        printf '  %-46s kept: %s\n' "$p" "$out" ;;
    esac
  done
fi

step "processes that survived their own disable"
live=$(running_names)
for p in $(cleanup_list); do
  printf '%s\n' "$live" | grep -qx "$p" || continue
  protected_by_policy "$p" && continue
  keep_pkg "$p" && continue
  uid=$(uid_of "$p")
  bg_off "$p"
  acmd am force-stop "$p" >/dev/null
  if [[ -n $uid && $uid -ge 10000 ]]; then
    acmd cmd netpolicy add restrict-background-blacklist "$uid" >/dev/null
  fi
  printf '  %-46s still running -> ops off, metered data blocked\n' "$p"
  printf '%s\tstarved\n' "$p" >> "$log"
done

# Android keeps pointing at a screensaver whose package is off, fails to start it,
# and puts the TV to sleep at the first timeout instead. That happens
# when the owner names Amazon's screensaver, and on TVs older lists debloated.
step "screensaver"
dream=$(dream_package)
if [[ -n $dream ]] && dev 'pm list packages -d' | tr -d '\r' | grep -qx "package:$dream"; then
  if present "${AOSP_DREAM%%/*}"; then
    acmd settings put secure screensaver_components "$AOSP_DREAM" >/dev/null
    printf '  %-46s %s\n' "$dream is off" "screensaver is now Android's own ($AOSP_DREAM)"
  else
    printf '  %-46s %s\n' "$dream is off" "no other screensaver on this TV: the screen sleeps at the timeout"
  fi
else
  printf '  %-46s %s\n' "${dream:-none set}" "unchanged"
fi

step "privacy settings"
while read -r ns key value; do
  [[ -z ${ns:-} || $ns == \#* ]] && continue
  acmd settings put "$ns" "$key" "$value" >/dev/null
  printf '  %-46s %s\n' "$key" "$value"
done < <(settings_files | sed 's/ = / /')

step "summary"
note "disabled $disabledn · protected $protectedn · system-disabled $sysoff · absent $absent · suspect $suspect · apps uninstalled $removed · enabled again $enabledn"
note "log: ${log#$ROOT/}"
[[ $suspect -gt 0 ]] && note "SUSPECT entries need a human look before continuing"

reboot_wait
[[ ${NO_REBOOT:-0} == 1 ]] && exit 0
note "home screen now: $(front_activity)"
note "if that is not a home screen you can use, run ./scripts/restore.sh"
