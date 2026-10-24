"""Shared helpers in scripts/lib.sh, run against a fake ADB.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]

# Prints the match first, then keeps writing like a long `cmd package help`.
# A reader that stops at the first match makes it die of SIGPIPE, as real adb does.
FAKE_ADB = r'''#!/usr/bin/env bash
case "$*" in
  start-server) exit 0 ;;
  "-s test-tv shell getprop ro.build.version.sdk") echo 30 ;;
  "-s test-tv shell cmd package help")
    echo "  install-existing [--user USER_ID] PACKAGE"
    sleep 0.2
    yes "  help text" | head -c 1000000 ;;
  *) exit 92 ;;
esac
'''


class NeedDeviceTest(unittest.TestCase):
    def test_install_existing_detected_under_pipefail(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o755)
            env = dict(os.environ, ADB=str(adb), ADB_SERIAL="test-tv")
            result = subprocess.run(
                ["bash", "-c",
                 'set -uo pipefail; source "$1"; need_device; echo "REINSTALL=$REINSTALL"',
                 "bash", str(ROOT / "scripts" / "lib.sh")],
                env=env, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("REINSTALL=1", result.stdout)


def lib(script, env=None):
    return subprocess.run(
        ["bash", "-c", 'set -uo pipefail; source "$1"; ' + script,
         "bash", str(ROOT / "scripts" / "lib.sh")],
        env=dict(os.environ, **(env or {})), capture_output=True, text=True,
    )


class FallbackDnsTest(unittest.TestCase):
    def line(self, upstream):
        result = lib('fallback_dns', {"DNS_UPSTREAM": upstream})
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_empty_keeps_the_router(self):
        self.assertEqual(self.line(""), "")

    def test_v4_and_v6(self):
        self.assertEqual(
            self.line("76.76.2.45 2606:1a40::45"),
            "76.76.2.45::53::UDP; [2606:1a40::45]::53::UDP",
        )

    def test_a_name_is_refused(self):
        result = lib('fallback_dns', {"DNS_UPSTREAM": "one.one.one.one"})
        self.assertNotEqual(result.returncode, 0)


class FireOsNameTest(unittest.TestCase):
    def name(self, sdk):
        return lib("fireos_name", {"SDK": str(sdk)}).stdout.strip()

    def test_each_generation(self):
        self.assertEqual(self.name(22), "Fire OS 5 or older (unsupported)")
        self.assertEqual(self.name(25), "Fire OS 6")
        self.assertEqual(self.name(28), "Fire OS 7")
        self.assertEqual(self.name(30), "Fire OS 8")
        self.assertEqual(self.name(34), "Fire OS newer than 8 (sdk 34, untested)")


AMAZON_ADB = "\n".join([
    "#!/usr/bin/env bash",
    'case "$*" in',
    "  '-s test-tv shell getprop ro.product.manufacturer') printf 'Amazon\\r\\n' ;;",
    "  *) exit 92 ;;",
    "esac",
]) + "\n"


class SupportedFireOsTest(unittest.TestCase):
    def run_with_sdk(self, sdk):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / "adb"
            adb.write_text(AMAZON_ADB)
            adb.chmod(0o755)
            return lib("need_supported_fireos && echo ok",
                       {"ADB": str(adb), "ADB_SERIAL": "test-tv", "SDK": str(sdk)})

    def test_fire_os_5_is_refused(self):
        result = self.run_with_sdk(22)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Fire OS 5", result.stderr)

    def test_fire_os_6_passes(self):
        result = self.run_with_sdk(25)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "ok")

    def test_dev1_strips_carriage_return(self):
        with tempfile.TemporaryDirectory() as tmp:
            adb = Path(tmp) / "adb"
            adb.write_text(AMAZON_ADB)
            adb.chmod(0o755)
            result = lib('[[ $(dev1 getprop ro.product.manufacturer) == Amazon ]] && echo exact',
                         {"ADB": str(adb), "ADB_SERIAL": "test-tv"})
        self.assertEqual(result.stdout.strip(), "exact", result.stderr)


if __name__ == "__main__":
    unittest.main()
