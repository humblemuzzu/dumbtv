"""scripts/library.sh against a pretend Fire TV: library.conf and the pictures it names reach
the launcher's library folder, --dry-run changes nothing, and a bad folder stops before the TV.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_wallpaper import path_without_magick

ROOT = Path(__file__).resolve().parents[1]
MEDIA = "/sdcard/Android/media/tv.dumbtv.home/library"
DATA = "/sdcard/Android/data/tv.dumbtv.home/files/library"
CONF = """# image | title | package | link
castle.jpg   | Castle   | com.hulu.plus     | https://www.hulu.com/series/castle
seinfeld.jpg | Seinfeld | com.netflix.ninja
castle.jpg   | Castle again | com.hulu.plus
gone.jpg     | Gone     | com.netflix.ninja
sub/up.jpg   | Up       | com.netflix.ninja
"""


class LibraryTest(unittest.TestCase):
    def run_script(self, *args, sdk=30, magick=False, installed=True, conf=CONF,
                   pictures=("castle.jpg", "seinfeld.jpg"), crop=None, folder=True):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        tmp = Path(holder.name)
        packages = {"tv.dumbtv.home": {"system": False, "enabled": True}} if installed else {}
        state = tmp / "state.json"
        pushed = tmp / "pushed"
        state.write_text(json.dumps({"packages": packages, "sdk": sdk, "pushed": str(pushed)}))
        log = tmp / "commands.log"
        log.touch()
        src = tmp / "titles"
        src.mkdir()
        if conf is not None:
            (src / "library.conf").write_text(conf)
        for name in pictures:
            (src / name).write_bytes(b"picture")
        if crop is not None:
            (src / "crop.conf").write_text(crop)
        path = path_without_magick(tmp / "no-magick")
        calls = tmp / "magick.log"
        if magick:
            # Stands in for ImageMagick: identify reports a 2000x600 picture, every other call
            # is logged and writes its last argument, so the crop can be checked.
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "magick"
            fake.write_text('#!/usr/bin/env bash\n'
                            'if [[ $1 == identify ]]; then echo "2000 600"; exit 0; fi\n'
                            f'echo "$*" >> "{calls}"\n'
                            'for a; do last=$a; done\nprintf fitted > "$last"\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            path = f"{bin_dir}{os.pathsep}{path}"
        env = dict(os.environ, PATH=path, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                   FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log))
        target = [str(src)] if folder else []
        result = subprocess.run(["bash", str(ROOT / "scripts" / "library.sh"), "-y", *args, *target],
                                env=env, capture_output=True, text=True, timeout=60)
        files = sorted(p.name for p in pushed.iterdir()) if pushed.exists() else []
        crops = calls.read_text().splitlines() if calls.exists() else []
        return result, log.read_text().splitlines(), files, pushed, crops

    def test_titles_reach_the_media_folder_on_fire_os_8(self):
        result, commands, files, pushed, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(files, ["castle.jpg", "library.conf", "seinfeld.jpg"])
        self.assertIn(f"rm -rf '{MEDIA}' && mkdir -p '{MEDIA}'", commands)
        self.assertIn("input keyevent KEYCODE_HOME", commands)
        self.assertEqual((pushed / "library.conf").read_text(), CONF)
        self.assertIn("missing picture: gone.jpg", result.stderr)
        self.assertIn("skipped: \"sub/up.jpg\"", result.stderr)

    def test_older_fire_os_uses_the_app_folder(self):
        result, commands, _, _, _ = self.run_script(sdk=28)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"rm -rf '{DATA}' && mkdir -p '{DATA}'", commands)

    def test_imagemagick_crops_each_picture_once(self):
        result, _, _, pushed, crops = self.run_script(magick=True, crop="seinfeld.jpg 0.5\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((pushed / "castle.jpg").read_text(), "fitted")
        cuts = [c for c in crops if "-crop" in c]
        self.assertEqual(len(cuts), 2, crops)
        # 2000x600 scaled to cover 1920x500 is cut 40 in from the side; castle keeps the
        # default 0.15 of the 100 spare rows, seinfeld the 0.5 crop.conf gives it.
        self.assertTrue(any("castle.jpg -crop 1920x500+40+15 " in c for c in cuts), cuts)
        self.assertTrue(any("seinfeld.jpg -crop 1920x500+40+50 " in c for c in cuts), cuts)

    def test_a_bad_crop_fraction_falls_back(self):
        result, _, _, _, crops = self.run_script(magick=True, crop="castle.jpg 7\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("not a number from 0 to 1", result.stderr)
        self.assertTrue(any("castle.jpg -crop 1920x500+40+15 " in c for c in crops), crops)

    def test_dry_run_changes_nothing(self):
        result, commands, files, _, crops = self.run_script("--dry-run", magick=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(files, [])
        self.assertEqual(crops, [])
        self.assertFalse(any(c.startswith(("rm", "mkdir", "input")) for c in commands), commands)

    def test_a_missing_conf_stops_before_the_tv(self):
        result, commands, files, _, _ = self.run_script(conf=None)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("library.conf not found", result.stderr)
        self.assertEqual((commands, files), ([], []))

    def test_no_pictures_stops_before_the_tv(self):
        result, commands, files, _, _ = self.run_script(pictures=())
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((commands, files), ([], []))

    def test_no_folder_is_an_error(self):
        result, commands, _, _, _ = self.run_script(folder=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, [])

    def test_needs_the_launcher(self):
        result, commands, files, _, _ = self.run_script(installed=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dumbtv home is not installed", result.stderr)
        self.assertFalse(any(c.startswith("rm") for c in commands))
        self.assertEqual(files, [])


if __name__ == "__main__":
    unittest.main()
