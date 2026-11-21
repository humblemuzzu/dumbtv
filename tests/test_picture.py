"""Unit tests for scripts/picture.py and picture settings inspection.

Run: pytest tests/test_picture.py
"""

from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import picture


class PictureToolTest(unittest.TestCase):
    def setUp(self):
        self.pq = picture.TvPictureQuality(serial="fake-serial", color=False)

    def test_port_map(self):
        self.assertEqual(picture.PORT_MAP.get("HW2"), "HDMI 1")
        self.assertEqual(picture.PORT_MAP.get("HW3"), "HDMI 2")
        self.assertEqual(picture.PORT_MAP.get("HW0"), "DTV (Antenna)")

    def test_format_table_single(self):
        params = {
            "Picture_Mode": "PC",
            "ALLM": "On",
            "SR_Strength": "0",
            "NR": "Off",
            "MPEG_NR": "Off",
        }
        table = self.pq.format_table("Test Profile", params)
        self.assertIn("=== Test Profile ===", table)
        self.assertIn("Picture Mode", table)
        self.assertIn("Auto Low Latency Mode (ALLM)", table)
        self.assertIn("PC", table)

    def test_format_table_with_preset_diff(self):
        live_params = {
            "Picture_Mode": "PC",
            "SR_Strength": "5",
            "NR": "Off",
        }
        preset_params = {
            "Picture_Mode": "PC",
            "SR_Strength": 0,
            "NR": "Off",
            "Color_Temperature": {
                "Standard": {},
                "Color_Temperature": "Standard",
            },
        }
        table = self.pq.format_table("Diff Test", live_params, preset_params)
        self.assertIn("Live / Active", table)
        self.assertIn("Preset Default", table)
        # SR_Strength differs (5 vs 0) so diff marker should appear
        self.assertIn("*", table)
        # Nested Color_Temperature extracted cleanly
        self.assertIn("Standard", table)

    def test_cli_help(self):
        cmd = [sys.executable, str(ROOT / "scripts" / "picture.py"), "--help"]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("--watch", res.stdout)
        self.assertIn("--preset", res.stdout)
        self.assertIn("--diff", res.stdout)


if __name__ == "__main__":
    unittest.main()
