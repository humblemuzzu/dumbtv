#!/usr/bin/env bash
# Puts wallpapers on dumbtv home from this computer, so nobody has to find the folder by hand.
# Each image is cropped to the TV's screen and scaled to its size when ImageMagick (magick) is
# installed, or pushed as it is otherwise (the launcher then crops it to the screen itself).
# The images go to the launcher's media folder, the one adb can write on every Fire OS, and a
# settings file there turns the image background on.
# Usage: ./scripts/wallpaper.sh [options] image...
#   --rotate off|visit|hourly|daily|daytime   how the launcher moves through several images
#   --dim 0|20|40|60|80                        darken the images so tiles stay readable
#   --crop center|top|bottom|left|right        which part of an image to keep (default center)
#   --shuffle                                  random names, so the rotation is a random order
#   --replace                                  remove the wallpapers already on the TV first
#   --dry-run  -y
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PKG=tv.dumbtv.home
DIR=/sdcard/Android/media/$PKG
ROTATE="" DIM="" CROP=center SHUFFLE=0 REPLACE=0
args=() images=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --rotate) ROTATE=${2:-}; shift ;;
    --dim) DIM=${2:-}; shift ;;
    --crop) CROP=${2:-}; shift ;;
    --shuffle) SHUFFLE=1 ;;
    --replace) REPLACE=1 ;;
    --dry-run|-y|--yes) args+=("$1") ;;
    -*) die "unknown flag: $1" ;;
    *) images+=("$1") ;;
  esac
  shift
done
parse_flags "${args[@]+"${args[@]}"}"

case $ROTATE in ''|off|visit|hourly|daily|daytime) ;; *) die "--rotate takes off, visit, hourly, daily or daytime" ;; esac
case $DIM in ''|0|20|40|60|80) ;; *) die "--dim takes 0, 20, 40, 60 or 80" ;; esac
case $CROP in
  center) GRAVITY=Center ;; top) GRAVITY=North ;; bottom) GRAVITY=South ;;
  left) GRAVITY=West ;; right) GRAVITY=East ;;
  *) die "--crop takes center, top, bottom, left or right" ;;
esac
[[ ${#images[@]} -gt 0 ]] || die "name at least one image: ./scripts/wallpaper.sh photo.jpg"
for f in "${images[@]}"; do
  [[ -f $f ]] || die "not a file: $f"
  case $(printf '%s' "${f##*.}" | tr 'A-Z' 'a-z') in
    jpg|jpeg|png|webp) ;;
    *) die "$f: the launcher reads .jpg, .jpeg, .png and .webp" ;;
  esac
done

need_device
present $PKG || die "dumbtv home is not installed: ./scripts/launcher.sh first"

# The physical screen, e.g. "Physical size: 1920x1080"; an override (Override size) wins.
size=$(dev 'wm size' | tr -d '\r' | sed -n 's/.*size: \([0-9]*x[0-9]*\).*/\1/p' | tail -1)
[[ $size =~ ^[0-9]+x[0-9]+$ ]] || size=1920x1080
MAGICK=""
if command -v magick >/dev/null 2>&1; then MAGICK=magick; fi

step "plan"
note "images     ${#images[@]}, into $DIR/wallpapers"
if [[ -n $MAGICK ]]; then
  note "fit        cropped ($CROP) and scaled to the screen, $size"
else
  note "fit        none: ImageMagick (magick) is not installed, so they go as they are"
  note "           and the launcher crops each one to the screen around its centre"
fi
[[ $SHUFFLE == 1 ]] && note "order      shuffled"
[[ $REPLACE == 1 ]] && note "replace    the wallpapers already on the TV are removed first"
note "settings   background=image${ROTATE:+, rotate=$ROTATE}${DIM:+, dim=$DIM}"
if [[ $DRY_RUN == 0 ]]; then confirm "Put these wallpapers on $SERIAL?" || die "cancelled"; fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-wall.XXXXXX") || die "could not create a temp dir"
trap 'rm -rf "$WORK"' EXIT

step "images"
n=0
for f in "${images[@]}"; do
  n=$((n + 1))
  base=$(basename "$f")
  ext=$(printf '%s' "${base##*.}" | tr 'A-Z' 'a-z')
  # Names keep to letters, digits, - and _: the launcher sorts by name, and rotate=daytime
  # reads the words in it (morning, afternoon, night).
  name=$(printf '%s' "${base%.*}" | tr -c 'A-Za-z0-9_-' '-')
  if [[ $SHUFFLE == 1 ]]; then name=$(printf '%05d-%s' "$((RANDOM % 100000))" "$name"); fi
  if [[ -n $MAGICK ]]; then
    out=$WORK/$name.jpg
    if [[ $DRY_RUN == 0 ]]; then
      "$MAGICK" "$f" -auto-orient -resize "$size^" -gravity "$GRAVITY" -extent "$size" \
        -strip -quality 90 "$out" || die "magick could not read $f"
    fi
  else
    out=$WORK/$name.$ext
    [[ $DRY_RUN == 1 ]] || cp "$f" "$out"
  fi
  printf '  %-40s -> wallpapers/%s\n' "$base" "$(basename "$out")"
done

step "push"
acmd "mkdir -p $DIR/wallpapers" >/dev/null
[[ $REPLACE == 1 ]] && acmd "rm -f $DIR/wallpapers/*" >/dev/null
if [[ $DRY_RUN == 1 ]]; then
  note "[dry-run] push the images and $DIR/home.conf"
  exit 0
fi
for out in "$WORK"/*; do
  "$ADB" -s "$SERIAL" push "$out" "$DIR/wallpapers/$(basename "$out")" >/dev/null ||
    die "push of $(basename "$out") failed"
done
note "$n pushed"

# A settings file in the media folder is merged in once, the next time the home screen comes
# back; a later change in Options is not overwritten by the same file.
{
  echo "background=image"
  [[ -n $ROTATE ]] && echo "rotate=$ROTATE"
  [[ -n $DIM ]] && echo "dim=$DIM"
} > "$WORK/home.conf"
"$ADB" -s "$SERIAL" push "$WORK/home.conf" "$DIR/home.conf" >/dev/null || die "push of home.conf failed"
dev "am start -n $PKG/.Home" >/dev/null 2>&1
note "done: Options › Next wallpaper steps through them"
