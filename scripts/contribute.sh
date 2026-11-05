#!/usr/bin/env bash
# End of session: record what you learned about this TV and open a PR.
# Reads the technical facts from the device, asks for what it cannot read (menu
# paths, blocking dialogs, your GitHub name for the credit), writes
# catalog/devices/<platform>-<brand>-<model>.yaml, validates it, and prints the PR steps.
#
# Usage: ./scripts/contribute.sh [-y] [--dry-run] [--id slug] [--github handle]
#        [--platform fireos|androidtv|tizen|webos|roku|other]
#        [--status tested|partial|untested] [--brand B] [--model M] [--no-device]
#        [--developer-mode "path"] [--ip-location "path"]
#        [--prompt "name|fix"] [--quirk "text"] [--models "A,B"] [--notes "text"]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ID=""; PLATFORM=""; STATUS=""; USE_DEVICE=1
DEV_MODE=""; IP_LOC=""; MODELS=""; NOTES=""; BRAND=""; MODEL=""; GITHUB=""
ANDROID="unknown"; SDK="unknown"; BUILD="unknown"; KERNEL="unknown"; DEVICE_SERIAL=""
PROMPTS=(); QUIRKS=()
while [[ $# -gt 0 ]]; do
  case $1 in
    -y|--yes|--dry-run|--no-device) ;;
    --*) [[ $# -ge 2 ]] || die "$1 takes a value" ;;
  esac
  case $1 in
    -y|--yes) ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --id) ID=$2; shift 2 ;;
    --github) GITHUB=${2#@}; shift 2 ;;
    --platform) PLATFORM=$2; shift 2 ;;
    --status) STATUS=$2; shift 2 ;;
    --brand) BRAND=$2; shift 2 ;;
    --model) MODEL=$2; shift 2 ;;
    --no-device) USE_DEVICE=0; shift ;;
    --developer-mode) DEV_MODE=$2; shift 2 ;;
    --ip-location) IP_LOC=$2; shift 2 ;;
    --models) MODELS=$2; shift 2 ;;
    --notes) NOTES=$2; shift 2 ;;
    --prompt) PROMPTS+=("$2"); shift 2 ;;
    --quirk) QUIRKS+=("$2"); shift 2 ;;
    *) die "unknown flag: $1" ;;
  esac
done

ask() { # prompt, default -> stdout
  local p=$1 d=${2:-} a
  if [[ $ASSUME_YES == 1 ]]; then printf '%s' "$d"; return; fi
  printf '%s' "$p" >&2
  [[ -n $d ]] && printf ' [%s]' "$d" >&2
  printf ' ' >&2
  read -r a
  printf '%s' "${a:-$d}"
}

# Anything that identifies a household never goes in the catalog: IP and MAC
# addresses, email addresses, and this TV's serial number.
identifies() {
  local ip first
  # A dotted quad that starts with one digit is a Fire OS version (7.6.9.7), and 127.x
  # and 0.0.0.0 are what a blocked host answers. Any other one is an address.
  for ip in $(printf '%s' "$1" | grep -oE '([0-9]{1,3}\.){3}[0-9]{1,3}'); do
    first=$((10#${ip%%.*}))
    [[ $first -ge 10 && $first != 127 ]] && return 0
  done
  printf '%s' "$1" | grep -Eq '([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}|[^[:space:]@]+@[^[:space:]@]+\.[A-Za-z]{2,}' ||
    [[ ${#DEVICE_SERIAL} -ge 6 && $1 == *"$DEVICE_SERIAL"* ]]
}
clean() {
  local v=$1
  while :; do
    if identifies "$v"; then
      # Unattended there is nobody to ask, and the value must not be written.
      [[ $ASSUME_YES == 1 ]] && die "a value looks like an IP, MAC or email address or the TV's serial number: '$v'"
      printf 'That looks like an IP, MAC or email address or the serial number. Remove it: ' >&2
      read -r v
      continue
    fi
    printf '%s' "$v"
    return
  done
}

# Double-quoted YAML scalar.
yamlq() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

step "this TV"
if [[ $USE_DEVICE == 1 ]]; then
  need_device
  # Fire TV Edition televisions report Amazon as manufacturer; some report the set's maker
  # as brand. A --brand given on the command line wins over both.
  [[ -z $BRAND ]] && BRAND=$(dev1 getprop ro.product.brand)
  [[ -z $BRAND ]] && BRAND=$(dev1 getprop ro.product.manufacturer)
  MODEL=$(dev1 getprop ro.product.model)
  ANDROID=$(dev1 getprop ro.build.version.release)
  SDK=$(dev1 getprop ro.build.version.sdk)
  BUILD=$(dev1 getprop ro.build.display.id)
  KERNEL=$(dev1 uname -r)
  DEVICE_SERIAL=$(dev1 getprop ro.serialno)
  note "brand    $BRAND"
  note "model    $MODEL"
  note "android  $ANDROID (sdk $SDK)"
  note "build    $BUILD"
  note "kernel   $KERNEL"
  [[ -z $PLATFORM ]] && { if is_fireos; then PLATFORM=fireos; else PLATFORM=androidtv; fi; }
  # The brand on the box is what a reader searches for.
  BRAND=$(ask "Brand on the box (Amazon for sticks and cubes):" "$BRAND")
else
  [[ -z $BRAND ]] && BRAND=$(ask "Brand:")
  [[ -z $MODEL ]] && MODEL=$(ask "Model:")
  ANDROID=$(ask "Android version:" "unknown")
  SDK=$(ask "SDK level:" "unknown")
  BUILD=$(ask "Build id:" "unknown")
  KERNEL=$(ask "Kernel:" "unknown")
  [[ -z $PLATFORM ]] && PLATFORM=$(ask "Platform (fireos/androidtv/tizen/webos/roku/other):" "fireos")
fi
[[ -z $STATUS ]] && STATUS=untested

if [[ $ASSUME_YES == 1 && ( -z $BRAND || -z $MODEL ) ]]; then
  die "unattended mode needs --brand and --model (or drop --no-device so they can be read from the TV)"
fi
slug() { printf '%s' "$1" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9.\n' '-' | sed 's/--*/-/g; s/^-//; s/-$//'; }
[[ -z $ID ]] && ID=$(printf '%s-%s-%s' "$PLATFORM" "$(slug "$BRAND")" "$(slug "$MODEL")")
printf '%s' "$ID" | grep -Eq '^[a-z0-9][a-z0-9.-]*$' || die "id '$ID' must be lowercase letters, digits, dots or dashes"

step "the parts only a human knows"
if [[ -z $DEV_MODE ]]; then
  case $PLATFORM in
    fireos) DEF="Settings > My Fire TV > About > click the device name 7 times, then My Fire TV > Developer Options > ADB debugging: ON" ;;
    androidtv) DEF="Settings > System > About > click Build 7 times, then Developer options > USB/Network debugging: ON" ;;
    *) DEF="" ;;
  esac
  DEV_MODE=$(clean "$(ask 'How did you enable developer mode and ADB?' "$DEF")")
fi
[[ -z $IP_LOC ]] && IP_LOC=$(clean "$(ask 'Where does the TV show its IP address?' 'Settings > About > Network')")
[[ -z $MODELS ]] && MODELS=$(clean "$(ask 'Other model names this applies to, comma separated:' '')")
if [[ $ASSUME_YES == 0 && ${#PROMPTS[@]} -eq 0 ]]; then
  while :; do
    p=$(ask "A dialog that blocked automation (name|fix), empty to stop:")
    [[ -z $p ]] && break
    PROMPTS+=("$(clean "$p")")
  done
fi
if [[ $ASSUME_YES == 0 && ${#QUIRKS[@]} -eq 0 ]]; then
  while :; do
    q=$(ask "Anything surprising worth warning the next person about, empty to stop:")
    [[ -z $q ]] && break
    QUIRKS+=("$(clean "$q")")
  done
fi
[[ -z $NOTES ]] && NOTES=$(clean "$(ask 'One-line summary of the result:' '')")
[[ -z $GITHUB ]] && GITHUB=$(ask "Your GitHub username, for the credit (Enter to stay anonymous):" "")
GITHUB=${GITHUB#@}
# Values given as flags were never asked for, so check everything once more. clean runs
# in a subshell here, so a refusal has to stop this shell too.
for v in BRAND MODEL DEV_MODE IP_LOC MODELS NOTES; do
  cleaned=$(clean "${!v}") || exit 1
  printf -v "$v" '%s' "$cleaned"
done
if [[ ${#PROMPTS[@]} -gt 0 ]]; then
  for i in "${!PROMPTS[@]}"; do PROMPTS[$i]=$(clean "${PROMPTS[$i]}") || exit 1; done
fi
if [[ ${#QUIRKS[@]} -gt 0 ]]; then
  for i in "${!QUIRKS[@]}"; do QUIRKS[$i]=$(clean "${QUIRKS[$i]}") || exit 1; done
fi
[[ -z $GITHUB || $GITHUB =~ ^[A-Za-z0-9][A-Za-z0-9-]*$ ]] || die "'$GITHUB' is not a GitHub username"

case $PLATFORM in
  fireos) DATA_DIR=data/fireos ;;
  androidtv) DATA_DIR=data/androidtv ;;
  *) DATA_DIR="" ;;
esac
OUT="$ROOT/catalog/devices/$ID.yaml"
[[ -e $OUT ]] && die "$OUT already exists: add your findings to it, or pass --id with the build appended (e.g. $ID-ps7717)"
[[ $DRY_RUN == 1 ]] && OUT=/dev/stdout

step "writing"
{
  printf 'id: %s\n' "$ID"
  printf 'title: "%s"\n' "$(yamlq "$BRAND $MODEL")"
  [[ -n $GITHUB ]] && printf 'contributors:\n  - %s\n' "$GITHUB"
  printf 'platform: %s\n' "$PLATFORM"
  printf 'status: %s\n' "$STATUS"
  printf 'tested_on: "%s"\n' "$(yamlq "$BUILD")"
  if [[ -n $MODELS ]]; then
    printf 'models:\n'
    printf '%s\n' "$MODELS" | tr ',' '\n' | sed 's/^ *//; s/ *$//' | sed '/^$/d' | while read -r m; do
      printf '  - "%s"\n' "$(yamlq "$m")"
    done
  else
    printf 'models: []\n'
  fi
  printf 'adb:\n  port: 5555\n  transport: wifi\n'
  printf 'developer_mode: >-\n  %s\n' "$DEV_MODE"
  printf 'ip_location: "%s"\n' "$(yamlq "$IP_LOC")"
  if [[ ${#PROMPTS[@]} -gt 0 ]]; then
    printf 'prompts:\n'
    for p in "${PROMPTS[@]}"; do
      name=${p%%|*}; fix=${p#*|}
      [[ $fix == "$p" ]] && fix=""
      printf '  - name: "%s"\n    blocks_automation: true\n    fix: "%s"\n' "$(yamlq "$name")" "$(yamlq "$fix")"
    done
  else
    printf 'prompts: []\n'
  fi
  if [[ -n $DATA_DIR ]]; then
    printf 'data:\n'
    printf '  packages: %s/packages.txt\n' "$DATA_DIR"
    printf '  protected: %s/protected.txt\n' "$DATA_DIR"
    printf '  never_disable: %s/never-disable.txt\n' "$DATA_DIR"
    printf '  user_apps: %s/user-apps.txt\n' "$DATA_DIR"
  else
    printf 'data: {}\n'
  fi
  if [[ ${#QUIRKS[@]} -gt 0 ]]; then
    printf 'quirks:\n'
    for q in "${QUIRKS[@]}"; do printf '  - "%s"\n' "$(yamlq "$q")"; done
  else
    printf 'quirks: []\n'
  fi
  printf 'notes: "%s"\n' "$(yamlq "${NOTES:-android $ANDROID, kernel $KERNEL}")"
  printf 'verify:\n  blocked: data/blocklists/verify-blocked.txt\n  allowed: data/blocklists/verify-allowed.txt\n'
} > "$OUT"

if [[ $DRY_RUN == 1 ]]; then
  note "dry-run: printed above, nothing written; it would be catalog/devices/$ID.yaml"
  exit 0
fi

note "${OUT#$ROOT/}"
python3 "$ROOT/catalog/validate.py" || die "the entry is invalid — fix the messages above, the file is at ${OUT#$ROOT/}"

step "open the PR"
note "git checkout -b add-$ID"
note "git add catalog/devices/$ID.yaml"
note "git commit -m \"catalog: $BRAND $MODEL\""
note "git push -u origin add-$ID"
note "gh pr create --fill"
note ""
note "or paste the file into a new issue if you would rather not use git"
