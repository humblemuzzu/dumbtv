#!/usr/bin/env bash
# Pushes the library banner's titles to dumbtv home.
# Usage: ./scripts/library.sh [--dry-run] [-y] <folder>
#
# <folder> holds library.conf and the pictures it names, one title per line:
#   image | title | package | deep link (optional) | key=value extras (optional)
# Pictures are cropped on this computer to the banner's shape (1920x500) so the TV only
# decodes what it shows: with ImageMagick (magick) where it is installed, else with sips on
# macOS, else they go as they are and the launcher crops them to the centre. The crop keeps
# the upper part of each picture, where faces usually are; an optional crop.conf in <folder>
# overrides that per picture, as "image fraction" (0 = top edge, 0.5 = centre, 1 = bottom
# edge). The TV's library folder is replaced.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PKG=tv.dumbtv.home
W=1920 H=500 FRAC=0.15
args=() SRC=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --dry-run|-y|--yes) args+=("$1") ;;
    -*) die "unknown flag: $1" ;;
    *) [[ -z $SRC ]] || die "name one folder: ./scripts/library.sh <folder>"; SRC=$1 ;;
  esac
  shift
done
parse_flags "${args[@]+"${args[@]}"}"
[[ -n $SRC ]] || die "name the folder that holds library.conf: ./scripts/library.sh <folder>"
[[ -f $SRC/library.conf ]] || die "$SRC/library.conf not found"

is_frac() { awk -v f="$1" 'BEGIN { exit !(f ~ /^(0|1|0?\.[0-9]+|1\.0*)$/) }'; }
frac_for() {
  local f=""
  [[ -f $SRC/crop.conf ]] && f=$(tr -d '\r' < "$SRC/crop.conf" | awk -v n="$1" '$1 == n {print $2; exit}')
  if [[ -n $f ]] && ! is_frac "$f"; then
    note "crop.conf: $1 has \"$f\", not a number from 0 to 1; using $FRAC" ; f=""
  fi
  printf '%s' "${f:-$FRAC}"
}

# The pictures library.conf names, each once and in its order. A name with a / is refused:
# the launcher looks for each picture beside library.conf.
images=() missing=0 taken="
"
while IFS= read -r line || [[ -n $line ]]; do
  line=${line%$'\r'}
  [[ -z ${line//[[:space:]]/} || $line == \#* ]] && continue
  img=$(printf '%s' "${line%%|*}" | awk '{$1=$1; print}')
  case $img in
    ''|*/*|.*) note "skipped: \"$img\" (keep each picture beside library.conf)"; continue ;;
  esac
  [[ $taken == *"
$img
"* ]] && continue
  taken="$taken$img
"
  if [[ ! -f $SRC/$img ]]; then
    note "missing picture: $img (the launcher skips that line)"; missing=$((missing + 1)); continue
  fi
  images+=("$img")
done < "$SRC/library.conf"
[[ ${#images[@]} -gt 0 ]] || die "none of the pictures in $SRC/library.conf is in $SRC"

FIT=none
if command -v magick >/dev/null 2>&1; then FIT=magick
elif command -v sips >/dev/null 2>&1; then FIT=sips
fi

need_device
present $PKG || die "dumbtv home is not installed: ./scripts/launcher.sh first"
# Fire OS 8 (Android 11) closes Android/data to adb; the launcher also reads its media folder.
if [[ $SDK -ge 30 ]]; then DEST=/sdcard/Android/media/$PKG/library
else DEST=/sdcard/Android/data/$PKG/files/library; fi

step "plan"
if [[ $missing -gt 0 ]]; then note "titles     ${#images[@]} pictures, $missing missing"
else note "titles     ${#images[@]} pictures"; fi
note "into       $DEST (replaced)"
case $FIT in
  magick|sips) note "fit        cropped to ${W}x${H} with $FIT" ;;
  none) note "fit        none: neither ImageMagick (magick) nor sips is installed, so the"
        note "           pictures go as they are and the launcher crops each to the centre" ;;
esac
if [[ $DRY_RUN == 0 ]]; then confirm "Replace the library banner on $SERIAL?" || die "cancelled"; fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-library.XXXXXX") || die "could not create a temp dir"
trap 'rm -rf "$WORK"' EXIT
tr -d '\r' < "$SRC/library.conf" > "$WORK/library.conf"

# Scales $1 so it covers WxH, then cuts a WxH band from it, $2 of the way down.
fit_magick() {
  local w h x y
  magick "$1" -auto-orient -resize "${W}x${H}^" +repage "$1" || return 1
  read -r w h < <(magick identify -format '%w %h\n' "$1" 2>/dev/null | head -1)
  [[ ${w:-} =~ ^[0-9]+$ && ${h:-} =~ ^[0-9]+$ ]] || return 1
  x=$(( (w - W) / 2 ))
  y=$(awk -v h="$h" -v H="$H" -v f="$2" 'BEGIN {printf "%d", (h - H) * f}')
  magick "$1" -crop "${W}x${H}+$x+$y" +repage -strip -quality 90 "$1"
}
fit_sips() {
  local w h x y
  w=$(sips -g pixelWidth "$1" | awk '/pixelWidth/ {print $2}')
  h=$(sips -g pixelHeight "$1" | awk '/pixelHeight/ {print $2}')
  [[ $w =~ ^[0-9]+$ && $h =~ ^[0-9]+$ ]] || return 1
  if (( w * H >= h * W )); then sips --resampleHeight "$H" "$1" >/dev/null || return 1
  else sips --resampleWidth "$W" "$1" >/dev/null || return 1; fi
  w=$(sips -g pixelWidth "$1" | awk '/pixelWidth/ {print $2}')
  h=$(sips -g pixelHeight "$1" | awk '/pixelHeight/ {print $2}')
  y=$(awk -v h="$h" -v H="$H" -v f="$2" 'BEGIN {printf "%d", (h - H) * f}')
  x=$(( (w - W) / 2 ))
  sips --cropToHeightWidth "$H" "$W" --cropOffset "$y" "$x" "$1" >/dev/null
}

step "pictures"
for img in "${images[@]}"; do
  frac=$(frac_for "$img")
  if [[ $DRY_RUN == 1 ]]; then printf '  %-40s %s\n' "$img" "[dry-run] ${FIT/none/as it is}"; continue; fi
  out=$WORK/$img
  cp "$SRC/$img" "$out" || die "could not read $SRC/$img"
  case $FIT in
    magick) fit_magick "$out" "$frac" || die "magick could not crop $img" ;;
    sips) fit_sips "$out" "$frac" || die "sips could not crop $img" ;;
  esac
  printf '  %-40s ready\n' "$img"
done

step "push"
acmd "rm -rf '$DEST' && mkdir -p '$DEST'" >/dev/null
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] push library.conf and ${#images[@]} pictures to $DEST"
  exit 0
fi
"$ADB" -s "$SERIAL" push "$WORK/library.conf" "$DEST/library.conf" >/dev/null || die "push of library.conf failed"
for img in "${images[@]}"; do
  "$ADB" -s "$SERIAL" push "$WORK/$img" "$DEST/$img" >/dev/null || die "push of $img failed"
done
note "${#images[@]} pictures pushed to $DEST"
dev input keyevent KEYCODE_HOME >/dev/null
note "done: the banner reloads now that the home screen came back"
