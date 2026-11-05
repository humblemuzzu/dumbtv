#!/usr/bin/env bash
# Pushes the library banner's titles to the TV (custom dumbtv home build only).
# Usage: ./scripts/library.sh <folder>
#
# <folder> holds library.conf and the pictures it names, one title per line:
#   image | title | package | deep link (optional) | key=value extras (optional)
# Pictures are cropped on this computer to the banner's shape (1920x500) so the TV only
# decodes what it shows. The crop keeps the upper part of each picture, where faces usually
# are; an optional crop.conf in <folder> overrides that per picture, as "image fraction"
# (0 = top edge, 0.5 = centre, 1 = bottom edge). The TV's library folder is replaced.
set -euo pipefail
ADB=${ADB:-adb}
SERIAL=${SERIAL:-${ADB_SERIAL:-}}
adb_() { if [[ -n $SERIAL ]]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

SRC=${1:?usage: $0 <folder with library.conf and pictures>}
[[ -f $SRC/library.conf ]] || { echo "error: $SRC/library.conf not found" >&2; exit 1; }
# macOS has sips; elsewhere the pictures go as they are and the TV crops them to the centre.
CROP=1
command -v sips >/dev/null || { CROP=0; echo "note: no sips (macOS only): pictures are pushed uncropped"; }

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-library.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
cp "$SRC/library.conf" "$WORK/library.conf"

n=0
while IFS= read -r line || [[ -n $line ]]; do
  line=${line%%$'\r'}
  [[ -z ${line// } || $line == \#* ]] && continue
  img=$(printf '%s' "${line%%|*}" | xargs)
  [[ -f $SRC/$img ]] || { echo "  missing picture: $img (line skipped on the TV)"; continue; }
  out="$WORK/$img"
  cp "$SRC/$img" "$out"
  if [[ $CROP == 0 ]]; then printf '  %-40s ready\n' "$img"; n=$((n + 1)); continue; fi
  w=$(sips -g pixelWidth "$out" | awk '/pixelWidth/ {print $2}')
  h=$(sips -g pixelHeight "$out" | awk '/pixelHeight/ {print $2}')
  # Scale so the picture covers 1920x500, then crop a band from it.
  if (( w * 500 >= h * 1920 )); then sips --resampleHeight 500 "$out" >/dev/null
  else sips --resampleWidth 1920 "$out" >/dev/null; fi
  w=$(sips -g pixelWidth "$out" | awk '/pixelWidth/ {print $2}')
  h=$(sips -g pixelHeight "$out" | awk '/pixelHeight/ {print $2}')
  frac=0.15
  if [[ -f $SRC/crop.conf ]]; then
    f=$(awk -v n="$img" '$1 == n {print $2}' "$SRC/crop.conf")
    [[ -n $f ]] && frac=$f
  fi
  y=$(awk -v h="$h" -v f="$frac" 'BEGIN {printf "%d", (h - 500) * f}')
  x=$(( (w - 1920) / 2 ))
  sips --cropToHeightWidth 500 1920 --cropOffset "$y" "$x" "$out" >/dev/null
  printf '  %-40s ready\n' "$img"
  n=$((n + 1))
done < "$WORK/library.conf"

sdk=$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')
if (( sdk >= 30 )); then DEST=/sdcard/Android/media/tv.dumbtv.home/library
else DEST=/sdcard/Android/data/tv.dumbtv.home/files/library; fi

adb_ shell "rm -rf '$DEST' && mkdir -p '$DEST'"
for f in "$WORK"/*; do
  adb_ push "$f" "$DEST/" 2>&1 | grep -v -E 'pushed|^$' || true
done
echo "pushed $n titles to $DEST"
adb_ shell input keyevent KEYCODE_HOME
echo "the banner reloads now that the home screen came back"
