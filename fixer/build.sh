#!/usr/bin/env bash
# Builds fixer/build/dumbtv-fixer.apk with the bare SDK tools, like launcher/build.sh.
# Signed with dumbtv home's key (launcher/release.keystore) so one key covers both apps.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

MIN_SDK=25
VERSION_CODE=5
VERSION_NAME=5.0

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK ]]; then
  for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    if [[ -f $d/platforms/android-$MIN_SDK/android.jar ]]; then SDK=$d; break; fi
  done
fi
JAR=$SDK/platforms/android-$MIN_SDK/android.jar
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
[[ -f $JAR ]] || { echo "error: $JAR missing — sdkmanager 'platforms;android-$MIN_SDK'" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: no build-tools in $SDK — sdkmanager 'build-tools;34.0.0'" >&2; exit 1; }

# Same JDK rule as launcher/build.sh: d8 from build-tools 34 crashed on JDK 27 output.
if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  for j in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk \
           "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [[ -n $j && -x $j/bin/javac ]]; then JAVA_HOME=$j; break; fi
  done
fi
[[ -x ${JAVA_HOME:-}/bin/javac ]] || { echo "error: no JDK 17 found — brew install openjdk@17" >&2; exit 1; }
export JAVA_HOME

KEYSTORE=${DUMBTV_KEYSTORE:-$PWD/../launcher/release.keystore}
KEYPASS=${DUMBTV_KEYPASS:-dumbtvhome}
[[ -f $KEYSTORE ]] || {
  echo "error: $KEYSTORE missing — build dumbtv home first (launcher/build.sh makes the key)" >&2
  exit 1
}

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-fixer.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" build

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" -o "$WORK/app.apk"

find src -name '*.java' > "$WORK/sources.txt"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/dumbtv-fixer.apk "$WORK/aligned.apk"
"$BT/apksigner" verify build/dumbtv-fixer.apk
printf 'built build/dumbtv-fixer.apk  %s bytes\n' "$(wc -c < build/dumbtv-fixer.apk | tr -d ' ')"
