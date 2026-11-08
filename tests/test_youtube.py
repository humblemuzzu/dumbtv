"""scripts/youtube.sh against a pretend Fire TV: SmartTube is installed, the stock app is
disabled (or removed when asked by name), and the home screen order changes only where adb
can reach it.

The download is answered by a stand-in for curl, and a stand-in for sha256sum reports the
pinned hash, so no APK is fetched.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = (ROOT / "scripts" / "youtube.sh").read_text()
PINNED = re.search(r"^APK_SHA256=([0-9a-f]{64})$", SCRIPT, re.M).group(1)
VERSION = int(re.search(r"^APK_VERSION_CODE=(\d+)$", SCRIPT, re.M).group(1))
SMARTTUBE = "org.smarttube.stable"
YT = "com.amazon.firetv.youtube"
HOME_CONF = "/sdcard/Android/data/tv.dumbtv.home/files/home.conf"

CURL = """#!/usr/bin/env bash
while [[ $# -gt 1 ]]; do [[ $1 == -o ]] && { out=$2; shift; }; shift; done
printf 'apk' > "$out"
"""
SHA256SUM = f"""#!/usr/bin/env bash
echo "{PINNED}  $1"
"""


class YouTubeTest(unittest.TestCase):
    def run_script(self, *flags, sdk=28, conf=None, crlf=False, launcher=True, youtube=True):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        tmp = Path(holder.name)
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        for name, body in (("curl", CURL), ("sha256sum", SHA256SUM)):
            (bin_dir / name).write_text(body)
            (bin_dir / name).chmod(0o755)
        packages = {}
        if youtube:
            packages[YT] = {"system": False, "enabled": True}
        if launcher:
            packages["tv.dumbtv.home"] = {"system": False, "enabled": True}
        state = tmp / "state.json"
        state.write_text(json.dumps({
            "packages": packages,
            "sdk": sdk,
            "crlf": crlf,
            "installs": {"package": SMARTTUBE, "versionCode": VERSION},
            "files": {} if conf is None else {HOME_CONF: conf},
            "pushed": str(tmp / "pushed"),
        }))
        log = tmp / "commands.log"
        log.touch()
        (tmp / "dumbtv.conf").write_text("YOUTUBE=1\n")
        env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                   FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log), CONF=str(tmp / "dumbtv.conf"),
                   REPORT=str(tmp / "report"), PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
        env.pop("YOUTUBE", None)
        result = subprocess.run(["bash", str(ROOT / "scripts" / "youtube.sh"), "-y", *flags],
                                env=env, capture_output=True, text=True, timeout=120)
        pushed = tmp / "pushed" / "home.conf"
        return (result, log.read_text().splitlines(), json.loads(state.read_text())["packages"],
                pushed.read_text() if pushed.exists() else None)

    def test_installs_smarttube_and_disables_youtube(self):
        result, _, packages, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(SMARTTUBE, packages)
        self.assertFalse(packages[YT]["enabled"])
        self.assertIn("disabled (the app is still installed", result.stderr)

    def test_reports_the_disable_when_adb_ends_lines_in_cr(self):
        result, _, packages, _ = self.run_script(crlf=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(packages[YT]["enabled"])
        self.assertIn("disabled (the app is still installed", result.stderr)
        self.assertNotIn("could not disable", result.stderr)

    def test_removes_youtube_only_when_asked(self):
        result, commands, packages, _ = self.run_script("--remove")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn(YT, packages)
        self.assertIn(f"pm uninstall --user 0 {YT}", commands)

    def test_puts_smarttube_first_and_youtube_out_of_the_order(self):
        conf = f"background=black\napps.order={YT},org.videolan.vlc\n"
        result, _, _, pushed = self.run_script(conf=conf)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(pushed, f"background=black\napps.order={SMARTTUBE},org.videolan.vlc\n")

    def test_drops_a_last_listed_youtube_when_adb_ends_lines_in_cr(self):
        conf = f"background=black\napps.order=org.videolan.vlc,{YT}\n"
        result, _, _, pushed = self.run_script(conf=conf, crlf=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(pushed, f"background=black\napps.order={SMARTTUBE},org.videolan.vlc\n")

    def test_fire_os_8_leaves_the_unreachable_settings_alone(self):
        conf = f"apps.order={YT}\n"
        result, commands, _, pushed = self.run_script(sdk=30, conf=conf)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIsNone(pushed)
        self.assertFalse(any("Android/data" in c for c in commands), commands)
        self.assertIn("end of the apps row", result.stderr)
        self.assertNotIn("no home.conf yet", result.stderr)

    def test_dry_run_changes_nothing(self):
        result, commands, packages, pushed = self.run_script("--dry-run")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn(SMARTTUBE, packages)
        self.assertTrue(packages[YT]["enabled"])
        self.assertIsNone(pushed)
        self.assertFalse(any(c.startswith("pm ") for c in commands), commands)


if __name__ == "__main__":
    unittest.main()
