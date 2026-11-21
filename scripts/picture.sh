#!/usr/bin/env bash
# Read and monitor TV Picture Quality (PQ) settings per input (HDMI 1-4, Tuner, Apps).
#
# Inspects:
#   - Currently tuned input and active picture mode (PC, Game, Standard, Custom)
#   - Live hardware parameters applied to the display (Sharpness, ALLM, MEMC, NR)
#   - Factory baseline presets from the TV's vendor database
#   - Real-time parameter changes via --watch as you configure an input with the remote
#
# Usage:
#   ./scripts/picture.sh                  # Inspect active input & live picture settings
#   ./scripts/picture.sh --watch          # Monitor live setting changes in real time
#   ./scripts/picture.sh --preset [SRC]   # Dump factory baseline preset (default: active input)
#   ./scripts/picture.sh --diff SRC1 SRC2 # Compare settings between inputs (e.g. HDMI1 HDMI2)
#   ./scripts/picture.sh --json           # Output in JSON format
#
# Flags:
#   --serial <serial>    Target device (defaults to $ADB_SERIAL or connected device)
#   --mode <mode>        Picture mode for preset inspection (default: PC)
#   --hdr <type>         Signal format for preset inspection (default: SDR)
set -uo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# Ensure python3 is available
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required to parse picture quality databases" >&2
  exit 1
fi

exec python3 "$ROOT/scripts/picture.py" "$@"
