"""scripts/wallpaper.sh against a pretend Fire TV: the images and the settings file reach the
launcher's media folder, and nothing else on the TV changes.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MEDIA = "/sdcard/Android/media/tv.dumbtv.home"


class WallpaperTest(unittest.TestCase):
    def run_script(self, *args, magick=False, installed=True, names=("beach.JPG", "night city.png")):
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        tmp = Path(holder.name)
        packages = {"tv.dumbtv.home": {"system": False, "enabled": True}} if installed else {}
        state = tmp / "state.json"
        pushed = tmp / "pushed"
        state.write_text(json.dumps({"packages": packages, "sdk": 30, "pushed": str(pushed)}))
        log = tmp / "commands.log"
        log.touch()
        for name in names:
            (tmp / name).write_bytes(b"image")
        path = os.environ["PATH"]
        if magick:
            # Stands in for ImageMagick: writes its last argument, so the fit step can be seen.
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "magick"
            fake.write_text('#!/usr/bin/env bash\nfor a; do last=$a; done\nprintf fitted > "$last"\n')
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            path = f"{bin_dir}{os.pathsep}{path}"
        env = dict(os.environ, PATH=path, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                   FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log))
        images = [str(tmp / name) for name in names]
        result = subprocess.run(["bash", str(ROOT / "scripts" / "wallpaper.sh"), "-y", *args, *images],
                                env=env, capture_output=True, text=True, timeout=60)
        files = sorted(p.name for p in pushed.iterdir()) if pushed.exists() else []
        conf = (pushed / "home.conf").read_text().split() if (pushed / "home.conf").exists() else []
        if conf:
            stamps = [line for line in conf if line.startswith("wallpaper.pushed=")]
            self.assertEqual(len(stamps), 1, conf)
            conf.remove(stamps[0])
        return result, log.read_text().splitlines(), files, conf, pushed

    def test_images_and_settings_reach_the_media_folder(self):
        result, commands, files, conf, _ = self.run_script("--rotate", "hourly", "--dim", "20")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(files, ["beach.jpg", "home.conf", "night-city.png"])
        self.assertEqual(conf, ["background=image", "rotate=hourly", "dim=20"])
        self.assertIn(f"mkdir -p {MEDIA}/wallpapers", commands)

    def test_settings_are_only_what_was_asked(self):
        result, _, _, conf, _ = self.run_script()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(conf, ["background=image"])

    def test_imagemagick_fits_each_image_to_a_jpeg(self):
        result, _, files, _, pushed = self.run_script(magick=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(files, ["beach.jpg", "home.conf", "night-city.jpg"])
        self.assertEqual((pushed / "beach.jpg").read_text(), "fitted")

    def test_images_with_the_same_name_all_arrive(self):
        result, _, files, _, _ = self.run_script(magick=True, names=("a.png", "a.jpg", "My Photo.jpg", "My-Photo.jpg"))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len([f for f in files if f != "home.conf"]), 4, files)

    def test_replace_clears_the_folder_first(self):
        result, commands, _, _, _ = self.run_script("--replace")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"rm -f {MEDIA}/wallpapers/*", commands)

    def test_shuffle_puts_a_number_in_front(self):
        result, _, files, _, _ = self.run_script("--shuffle")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        walls = [f for f in files if f != "home.conf"]
        self.assertEqual(len(walls), 2)
        for f in walls:
            self.assertRegex(f, r"^\d{5}-(beach\.jpg|night-city\.png)$")

    def test_dry_run_pushes_nothing(self):
        result, commands, files, _, _ = self.run_script("--dry-run")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(files, [])
        self.assertFalse(any(c.startswith(("mkdir", "rm", "am start")) for c in commands))

    def test_a_bad_value_stops_before_the_tv(self):
        result, commands, files, _, _ = self.run_script("--dim", "50")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(commands, [])
        self.assertEqual(files, [])

    def test_needs_the_launcher(self):
        result, _, files, _, _ = self.run_script(installed=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dumbtv home is not installed", result.stderr)
        self.assertEqual(files, [])


if __name__ == "__main__":
    unittest.main()
