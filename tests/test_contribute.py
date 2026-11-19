"""contribute.sh keeps household identifiers out of the catalog, however they arrive.

Run: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]


def contribute(*flags):
    return subprocess.run(
        ["bash", str(ROOT / "scripts" / "contribute.sh"), "--no-device", "-y", "--dry-run",
         "--brand", "Acme", "--model", "T1", *flags],
        capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60,
    )


class ContributeTest(unittest.TestCase):
    def test_identifiers_given_as_flags_are_refused(self):
        # Built at run time: CI refuses private addresses written out anywhere in the repo.
        def ip(*octets):
            return ".".join(map(str, octets))
        for flags in (["--notes", "my router is " + ip(192, 168, 1, 20)],
                      ["--notes", "ask me at someone@example.com"],
                      ["--quirk", "MAC " + ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])],
                      ["--quirk", "MAC " + "-".join(["AA", "BB", "CC", "DD", "EE", "FF"])],
                      ["--prompt", "Allow USB debugging?|press OK, then adb connect " + ip(10, 0, 0, 5)],
                      ["--ip-location", "Settings > Network shows " + ip(172, 16, 4, 2)]):
            with self.subTest(flags=flags):
                result = contribute(*flags)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn("looks like", result.stderr)
                self.assertNotIn(flags[1], result.stdout)

    def test_versions_and_blocked_answers_are_not_addresses(self):
        result = contribute("--notes", "Fire OS 7.6.9.7: blocked hosts answered 127.0.0.1 or 0.0.0.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('notes: "Fire OS 7.6.9.7', result.stdout)

    def test_a_flag_without_its_value_is_named(self):
        result = contribute("--notes")
        self.assertEqual(result.returncode, 1)
        self.assertIn("--notes takes a value", result.stderr)

    def test_brand_on_the_box_names_the_file(self):
        # Fire TV Edition sets report Amazon as manufacturer; the set's maker is in ro.product.brand.
        import json, os, tempfile
        for brand_flag, want in (([], "fireos-hisense-aftzz999"), (["--brand", "Toshiba"], "fireos-toshiba-aftzz999")):
            with self.subTest(flags=brand_flag), tempfile.TemporaryDirectory() as tmp:
                state = Path(tmp) / "state.json"
                state.write_text(json.dumps({"packages": {}, "sdk": 30, "props": {
                    "ro.product.brand": "Hisense", "ro.product.model": "AFTZZ999"}}))
                env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                           FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(Path(tmp) / "log"))
                result = subprocess.run(
                    ["bash", str(ROOT / "scripts" / "contribute.sh"), "-y", "--dry-run", *brand_flag],
                    env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn(f"id: {want}", result.stdout)

    def test_a_model_already_in_the_catalog_gets_its_build_in_the_name(self):
        # Refusing it only after every question had been answered was #206.
        import json, os, tempfile
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "state.json"
            state.write_text(json.dumps({"packages": {}, "sdk": 25, "props": {
                "ro.product.brand": "Amazon", "ro.product.model": "AFTMM",
                "ro.build.display.id": "NS6711"}}))
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(Path(tmp) / "log"))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "contribute.sh"), "-y", "--dry-run"],
                env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("id: fireos-amazon-aftmm-ns6711", result.stdout)
        self.assertIn("fireos-amazon-aftmm.yaml exists already", result.stderr)
        # Fire OS 6 reads its own lists, as the scripts do on it.
        self.assertIn("  packages: data/fireos6/packages.txt\n", result.stdout)
        self.assertIn("  user_apps: data/fireos/user-apps.txt\n", result.stdout)

    def test_an_id_that_exists_stops_before_the_questions(self):
        result = subprocess.run(
            ["bash", str(ROOT / "scripts" / "contribute.sh"), "--no-device", "--brand", "Amazon",
             "--model", "AFTMM", "--id", "fireos-amazon-aftmm"],
            capture_output=True, text=True, input="\n" * 20, timeout=60)
        self.assertEqual(result.returncode, 1)
        self.assertIn("fireos-amazon-aftmm.yaml already exists", result.stderr)
        self.assertNotIn("developer mode", result.stderr)

    def test_every_existing_entry_passes(self):
        # What is already in the catalog was reviewed by hand; the filter must not refuse it.
        lines = [line for f in sorted((ROOT / "catalog" / "devices").glob("*.yaml"))
                 for line in f.read_text().splitlines()]
        check = ('source scripts/lib.sh; DEVICE_SERIAL=""; '
                 + subprocess.run(["sed", "-n", "/^identifies()/,/^}/p",
                                   str(ROOT / "scripts" / "contribute.sh")],
                                  capture_output=True, text=True, check=True).stdout
                 + '\nwhile IFS= read -r l; do identifies "$l" && printf "%s\\n" "$l"; done; true')
        result = subprocess.run(["bash", "-c", check], input="\n".join(lines) + "\n",
                                cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
