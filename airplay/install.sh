#!/bin/bash
# Installs the AirPlay sweeper as a launchd agent for the current macOS user: sweep.sh runs from
# login on, kept alive by launchd; it watches the TV's event log and reconnects by itself.
#   ADB_SERIAL=<tv-ip>:5555 ./airplay/install.sh   install or update
#   ./airplay/install.sh --undo                    remove
# The machine's adb key must already be allowed on the TV (adb connect once, accept the prompt).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

LABEL=tv.dumbtv.airplay-sweeper
PLIST=$HOME/Library/LaunchAgents/$LABEL.plist
DOMAIN=gui/$(id -u)

launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
if [[ ${1:-} == --undo ]]; then
  rm -f "$PLIST"
  echo "removed $LABEL"
  exit 0
fi
[[ -z ${1:-} ]] || { echo "usage: $0 [--undo]" >&2; exit 2; }

ADB=$(command -v adb || true)
[[ -n $ADB ]] || { echo "error: adb not found — brew install android-platform-tools" >&2; exit 1; }
TV=${ADB_SERIAL:-}
[[ -n $TV ]] || { echo "error: set ADB_SERIAL=<tv-ip>:5555" >&2; exit 1; }

mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>$PWD/sweep.sh</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>ADB</key><string>$ADB</string>
    <key>ADB_SERIAL</key><string>$TV</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>ProcessType</key><string>Standard</string>
  <key>LowPriorityIO</key><true/>
  <key>StandardErrorPath</key><string>$HOME/Library/Logs/dumbtv-airplay-sweeper.err</string>
</dict>
</plist>
EOF
launchctl bootstrap "$DOMAIN" "$PLIST"
sleep 2
launchctl print "$DOMAIN/$LABEL" | grep -E '^\s*(state|last exit code|runs)' || true
echo "installed $LABEL (log: ~/Library/Logs/dumbtv-airplay-sweeper.log)"
