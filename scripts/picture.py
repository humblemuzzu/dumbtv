#!/usr/bin/env python3
"""dumbtv picture: inspect and monitor Picture Quality (PQ) settings per input.

Works on Fire OS / MediaTek televisions over ADB with no root.
Reads:
  1. Live active input state and hardware parameters from the TV.
  2. Factory baseline preset databases from /vendor/tvconfig/.../presetdb/.
  3. Real-time setting adjustments via --watch as you configure an input with the remote.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time

# Key parameters grouped by feature domain
PARAM_CATEGORIES = {
    "Mode & Gaming": [
        ("Picture_Mode", "Picture Mode"),
        ("ALLM", "Auto Low Latency Mode (ALLM)"),
        ("PC_Mode", "PC Mode (4:4:4 bypass)"),
        ("Game_Mode", "Game Mode"),
    ],
    "Clarity & Sharpness": [
        ("SR_Strength", "Sharpness (VIP Peaking)"),
        ("AISR", "Super Resolution (AISR)"),
        ("EDGE_ENHANCER", "Edge Enhancer"),
        ("ULTRA_CLEAR", "Ultra Clear Detail"),
    ],
    "Noise Reduction": [
        ("MPEG_NR", "MPEG Noise Reduction (DBK)"),
        ("NR", "Noise Reduction (Spatial/Temporal)"),
        ("ABF", "Debanding Filter (ABF)"),
    ],
    "Motion & Cadence": [
        ("MJC_Effect", "Motion Smoothing (MEMC)"),
        ("MJC_Level_Cus", "MEMC Custom Level"),
        ("MJC_Dejudder", "De-judder"),
        ("MJC_Deblur", "De-blur"),
        ("Real_Cinema", "Natural Cinema (24p pull-down)"),
    ],
    "Contrast & Brightness": [
        ("Backlight", "Backlight Level"),
        ("Brightness", "Brightness (Black Level)"),
        ("Contrast", "Contrast (White Level)"),
        ("LD_MODE", "Local Dimming"),
        ("DLC_STRENGTH", "Dynamic Contrast"),
        ("Local_Contrast", "Local Contrast Enhancement"),
        ("HDR_ENHANCER", "HDR Contrast Enhancer"),
        ("Gamma", "Gamma Curve"),
    ],
    "Color & Tint": [
        ("Color_Temperature", "Color Temperature"),
        ("Saturation", "Color Saturation"),
        ("Hue", "Tint / Hue"),
        ("Dynamic_Color_Booster", "Dynamic Color"),
        ("Flesh_Tone", "Flesh Tone Correction"),
        ("Color_Space", "Color Space / Gamut"),
    ],
}

PORT_MAP = {
    "HW0": "DTV (Antenna)",
    "HW1": "Analog (Cable)",
    "HW2": "HDMI 1",
    "HW3": "HDMI 2",
    "HW4": "HDMI 3",
    "HW5": "Composite (AV)",
    "HDMI100004": "HDMI (CEC)",
}

SOURCE_NAME_MAP = {
    "HDMI 1": "HDMI1",
    "HDMI 2": "HDMI2",
    "HDMI 3": "HDMI3",
    "HDMI 4": "HDMI4",
    "DTV": "DTV",
    "Antenna": "DTV",
    "App": "App",
    "Ambient": "Ambient",
    "Composite": "COMPOSITE",
}

KNOWN_MODES = {
    "standard", "pc", "custom", "game", "movie", "vivid", "sport",
    "user", "dynamic", "natural", "filmmaker", "dolbyvisionbright",
    "dolbyvisiondark", "dolbyvisioniq", "dolbyvisiongame", "dolbyvisionpc",
    "dolbyvisioncustom"
}


class Colors:
    def __init__(self, enabled=True):
        self.enabled = enabled and sys.stdout.isatty()

    def blue(self, s): return f"\033[94m{s}\033[0m" if self.enabled else s
    def green(self, s): return f"\033[92m{s}\033[0m" if self.enabled else s
    def yellow(self, s): return f"\033[93m{s}\033[0m" if self.enabled else s
    def red(self, s): return f"\033[91m{s}\033[0m" if self.enabled else s
    def bold(self, s): return f"\033[1m{s}\033[0m" if self.enabled else s
    def dim(self, s): return f"\033[2m{s}\033[0m" if self.enabled else s


class TvPictureQuality:
    def __init__(self, serial=None, adb_bin=None, color=True):
        self.adb = adb_bin or os.environ.get("ADB", "adb")
        self.serial = serial or os.environ.get("ADB_SERIAL") or os.environ.get("SERIAL")
        self.colors = Colors(color)
        self._preset_dir = None

    def _adb_cmd(self, *args):
        cmd = [self.adb]
        if self.serial:
            cmd.extend(["-s", self.serial])
        cmd.extend(args)
        return cmd

    def run_adb(self, *args, check=True):
        cmd = self._adb_cmd(*args)
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if check and res.returncode != 0:
            raise RuntimeError(f"ADB error ({res.returncode}): {res.stderr.strip()}")
        return res.stdout

    def get_active_input(self):
        """Identify what input is currently tuned and active on screen."""
        out = self.run_adb("shell", "dumpsys", "tv_input", check=False)
        active_id = None
        active_port = "Unknown"
        active_hw = None

        # Look in sessionStateMap for active hardware input
        m = re.search(r"inputId:\s*com\.mediatek\.tis/\.(HdmiInputService|CompositeInputService|AnalogInputService|AtscTunerInputService)/([A-Za-z0-9]+)", out)
        if m:
            active_id = m.group(0).split()[-1]
            active_hw = m.group(2)
            active_port = PORT_MAP.get(active_hw, active_hw)
        else:
            # Fallback: check logcat for last stream counter / Current Source
            log_out = self.run_adb("logcat", "-d", "-t", "500", check=False)
            for l in reversed(log_out.splitlines()):
                m_src = re.search(r"Current Source:\s*([A-Za-z0-9_]+)", l)
                if m_src:
                    src_raw = m_src.group(1)
                    active_port = src_raw
                    active_hw = src_raw
                    break

        return {
            "id": active_id,
            "port": active_port,
            "hw": active_hw,
            "source_key": SOURCE_NAME_MAP.get(active_port, active_hw or "HDMI1"),
        }

    def get_live_parameters(self):
        """Fetch the most recent active PQ parameters applied to the display."""
        log_out = self.run_adb("logcat", "-d", check=False)
        params = {}
        active_source = None
        active_mode = None
        active_hdr = None

        for line in log_out.splitlines():
            if "Current Source:" in line:
                m = re.search(r"Current Source:\s*([A-Za-z0-9_]+)", line)
                if m:
                    active_source = m.group(1)
            if "PqCache" in line:
                m_mode = re.search(r"<([A-Za-z0-9_]+),\s*([A-Za-z0-9_]+),\s*([A-Za-z0-9_]+)", line)
                if m_mode:
                    active_source = m_mode.group(1)
                    active_hdr = m_mode.group(2)
                m_set = re.search(r"set\(([A-Za-z0-9_]+),\s*([^)]+)\)", line)
                if m_set:
                    params[m_set.group(1)] = m_set.group(2).strip()
            m_get = re.search(r"MTSPQ-OEMService: defaultGetCall, fetched value for ([A-Za-z0-9_]+)\s*:\s*(.+)", line)
            if m_get:
                params[m_get.group(1)] = m_get.group(2).strip()

        active_mode = params.get("Picture_Mode", active_mode)
        active_hdr = params.get("HdrType", active_hdr or "SDR")

        return {
            "source": active_source,
            "mode": active_mode,
            "hdr": active_hdr,
            "params": params,
        }

    def find_preset_dir(self):
        """Locate the /vendor/tvconfig/.../presetdb directory on the TV."""
        if self._preset_dir:
            return self._preset_dir

        # Try to find model-specific dir first
        model_out = self.run_adb("shell", "getprop", "ro.vendor.sys.idme.mfr_model", check=False).strip()
        short_model = model_out.split("-")[-1].replace("NA26", "").replace("CA26", "") if model_out else ""

        check_paths = [
            f"/vendor/tvconfig/model/hydrot/{short_model}/pq/presetdb" if short_model else None,
            "/vendor/tvconfig/model/hydrot/NS55F501/pq/presetdb",
            "/vendor/tvconfig/bsp/chip/mt5873/pq/presetdb",
        ]
        for p in check_paths:
            if not p:
                continue
            out = self.run_adb("shell", f"[ -d '{p}' ] && echo 'found'", check=False).strip()
            if "found" in out:
                self._preset_dir = p
                return p

        # Generic search
        out = self.run_adb("shell", "ls -d /vendor/tvconfig/*/*/pq/presetdb 2>/dev/null", check=False).splitlines()
        for candidate in out:
            candidate = candidate.strip()
            if candidate:
                self._preset_dir = candidate
                return candidate

        return None

    def get_preset_settings(self, source="HDMI1", mode="Standard", hdr="SDR"):
        """Read factory preset JSON for a source, picture mode, and HDR format."""
        preset_dir = self.find_preset_dir()
        if not preset_dir:
            return {}

        json_path = f"{preset_dir}/{source}_initial.json"
        raw = self.run_adb("shell", "cat", json_path, check=False)
        try:
            data = json.loads(raw)
            pq_val = data.get("Preset_PQ_Value", {})
            return pq_val.get(mode, {}).get(hdr, {})
        except Exception:
            return {}

    def get_available_preset_modes(self, source="HDMI1"):
        """List all available picture modes in the preset database."""
        preset_dir = self.find_preset_dir()
        if not preset_dir:
            return []
        raw = self.run_adb("shell", "cat", f"{preset_dir}/{source}_initial.json", check=False)
        try:
            data = json.loads(raw)
            pq_val = data.get("Preset_PQ_Value", {})
            return [k for k in pq_val.keys() if not k.startswith("$")]
        except Exception:
            return []

    def format_table(self, title, params, preset_params=None):
        """Format parameters cleanly grouped by category."""
        lines = []
        lines.append(self.colors.bold(f"=== {title} ==="))
        lines.append("")

        has_preset = preset_params is not None
        if has_preset:
            header = f"{'Setting':<38} {'Live / Active':<16} {'Preset Default':<16}"
        else:
            header = f"{'Setting':<38} {'Value':<20}"
        lines.append(self.colors.dim(header))
        lines.append(self.colors.dim("-" * len(header)))

        for category, items in PARAM_CATEGORIES.items():
            cat_lines = []
            for key, label in items:
                live_val = params.get(key)
                if live_val is None and not has_preset:
                    continue
                preset_val = preset_params.get(key) if has_preset else None
                if live_val is None and preset_val is None:
                    continue

                # Format nested dictionary for Color_Temperature if present
                if isinstance(live_val, dict):
                    live_val = live_val.get("Color_Temperature", "Standard")
                if isinstance(preset_val, dict):
                    preset_val = preset_val.get("Color_Temperature", "Standard")

                live_str = str(live_val) if live_val is not None else "-"
                if has_preset:
                    preset_str = str(preset_val) if preset_val is not None else "-"
                    diff_marker = "  "
                    formatted_live = f"{live_str:<16}"
                    if live_val is not None and preset_val is not None and str(live_val) != str(preset_val):
                        diff_marker = "* "
                        formatted_live = f"{self.colors.yellow(live_str):<25}"
                    row = f"  {label:<36} {formatted_live} {preset_str:<16} {diff_marker}"
                else:
                    row = f"  {label:<36} {self.colors.green(live_str):<20}"
                cat_lines.append(row)

            if cat_lines:
                lines.append(self.colors.bold(f"[{category}]"))
                lines.extend(cat_lines)
                lines.append("")

        return "\n".join(lines)

    def watch(self):
        """Stream real-time setting and input changes from logcat."""
        print(self.colors.bold("Monitoring Picture Quality changes in real time... (Ctrl+C to stop)"))
        print(self.colors.dim("Adjust picture settings with the TV remote to see live parameter updates."))
        print("-" * 75)

        cmd = self._adb_cmd("logcat", "-v", "time", "-T", "1")
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)

        try:
            for line in proc.stdout:
                line = line.strip()
                if "Current Source:" in line:
                    m = re.search(r"Current Source:\s*([A-Za-z0-9_]+)", line)
                    if m:
                        t = line[:18]
                        print(f"[{t}] {self.colors.blue('INPUT SWITCH')}: Current Source -> {self.colors.bold(m.group(1))}")
                elif "defaultSetCall, successfully set" in line:
                    m = re.search(r"successfully set\s*:\s*([A-Za-z0-9_]+)\s*:\s*(.+)", line)
                    if m:
                        t = line[:18]
                        k, v = m.group(1), m.group(2).strip()
                        print(f"[{t}] {self.colors.green('SET')}: {k:<25} = {self.colors.bold(v)}")
                elif "PqCache" in line and "set(" in line:
                    m = re.search(r"set\(([A-Za-z0-9_]+),\s*([^)]+)\)", line)
                    if m:
                        t = line[:18]
                        k, v = m.group(1), m.group(2).strip()
                        if k != "Source":
                            print(f"[{t}] {self.colors.yellow('PQ_CACHE')}: {k:<23} = {self.colors.bold(v)}")
        except KeyboardInterrupt:
            print("\nStopped monitoring.")
        finally:
            proc.terminate()


def main():
    parser = argparse.ArgumentParser(description="dumbtv picture: inspect and monitor TV picture quality settings.")
    parser.add_argument("--serial", help="Target device serial (defaults to $ADB_SERIAL)")
    parser.add_argument("--watch", action="store_true", help="Monitor live setting changes in real time as you adjust them")
    parser.add_argument("--preset", nargs="?", const="AUTO", help="Inspect factory baseline presets (e.g. HDMI1, HDMI2, DTV)")
    parser.add_argument("--source", help="Input source (e.g. HDMI1, HDMI2, DTV, App)")
    parser.add_argument("--mode", default="PC", help="Picture mode for preset inspection (default: PC)")
    parser.add_argument("--hdr", default="SDR", help="HDR format for preset inspection (default: SDR)")
    parser.add_argument("--diff", nargs=2, metavar=("SRC1", "SRC2"), help="Diff settings between two inputs (e.g. --diff HDMI1 HDMI2)")
    parser.add_argument("--json", action="store_true", help="Output results in JSON format")
    parser.add_argument("--no-color", action="store_true", help="Disable color output")
    args = parser.parse_args()

    pq = TvPictureQuality(serial=args.serial, color=not args.no_color)

    # 1. Real-time watch mode
    if args.watch:
        pq.watch()
        return 0

    # 2. Diff mode
    if args.diff:
        arg1, arg2 = args.diff
        is_mode_diff = arg1.lower() in KNOWN_MODES or arg2.lower() in KNOWN_MODES

        if is_mode_diff:
            source = args.source or pq.get_active_input().get("source_key", "HDMI1")
            mode1 = arg1
            mode2 = arg2
            p1 = pq.get_preset_settings(source, mode1, args.hdr)
            p2 = pq.get_preset_settings(source, mode2, args.hdr)
            title = f"Diff: Mode {mode1} vs {mode2} ({source}, HDR: {args.hdr})"
            col1, col2 = f"{mode1}", f"{mode2}"
        else:
            source1 = arg1
            source2 = arg2
            mode = args.mode
            p1 = pq.get_preset_settings(source1, mode, args.hdr)
            p2 = pq.get_preset_settings(source2, mode, args.hdr)
            title = f"Diff: {source1} vs {source2} (Mode: {mode}, HDR: {args.hdr})"
            col1, col2 = f"{source1}", f"{source2}"

        if args.json:
            print(json.dumps({"diff": {col1: p1, col2: p2}}, indent=2))
        else:
            diff_dict = {}
            for k in set(p1.keys()).union(p2.keys()):
                v1, v2 = p1.get(k), p2.get(k)
                if isinstance(v1, dict):
                    v1 = v1.get("Color_Temperature", "Standard")
                if isinstance(v2, dict):
                    v2 = v2.get("Color_Temperature", "Standard")
                if v1 != v2:
                    diff_dict[k] = (v1, v2)
            print(pq.colors.bold(f"=== {title} ==="))
            if not diff_dict:
                print(pq.colors.green("No differences found."))
            else:
                print(f"{'Parameter':<35} {col1:<20} {col2:<20}")
                print("-" * 75)
                for k, (v1, v2) in sorted(diff_dict.items()):
                    print(f"  {k:<33} {str(v1):<20} {str(v2):<20}")
        return 0

    # 3. Preset inspection mode
    if args.preset:
        source = args.preset
        if source == "AUTO":
            active_info = pq.get_active_input()
            source = active_info.get("source_key", "HDMI1")
        preset_data = pq.get_preset_settings(source, args.mode, args.hdr)
        if args.json:
            print(json.dumps({source: {args.mode: {args.hdr: preset_data}}}, indent=2))
        else:
            print(pq.format_table(f"Baseline Preset: {source} (Mode: {args.mode}, HDR: {args.hdr})", preset_data))
        return 0

    # 4. Default: Live active input inspection
    active_input = pq.get_active_input()
    live = pq.get_live_parameters()
    source_key = live.get("source") or active_input.get("source_key", "HDMI1")
    mode_key = live.get("mode") or "Standard"
    hdr_key = live.get("hdr") or "SDR"

    preset_data = pq.get_preset_settings(source_key, mode_key, hdr_key)

    if args.json:
        result = {
            "active_input": active_input,
            "live_profile": live,
            "preset_baseline": preset_data,
        }
        print(json.dumps(result, indent=2))
    else:
        print(pq.colors.bold(f"Active Port: {active_input['port']} (Session ID: {active_input['id'] or 'running'})"))
        print(pq.colors.bold(f"Active Mode: {mode_key} · Signal: {hdr_key}"))
        print(pq.colors.dim(f"Preset Source Key: {source_key}"))
        print("")
        print(pq.format_table(f"Live Picture Settings ({active_input['port']})", live["params"], preset_data if preset_data else None))

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
