"""launcher/install.sh --undo against a pretend Fire TV: everything the install changed
goes back.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GUARD = "tv.dumbtv.home/tv.dumbtv.home.Guard"


class LauncherUndoTest(unittest.TestCase):
    def undo(self, settings, sdk=28):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            state = tmp / "state.json"
            state.write_text(json.dumps({"packages": {"tv.dumbtv.home": {"system": False, "enabled": True}},
                                         "settings": settings, "sdk": sdk}))
            log = tmp / "commands.log"
            log.touch()
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log))
            result = subprocess.run(["bash", str(ROOT / "launcher" / "install.sh"), "--undo"],
                                    env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return log.read_text().splitlines(), json.loads(state.read_text())["settings"]

    def test_an_earlier_min_futurity_comes_back(self):
        _, settings = self.undo({"global/alarm_manager_constants": "min_futurity=0,other=1",
                                 "global/dumbtv_min_futurity": "5000",
                                 "secure/enabled_accessibility_services": GUARD})
        self.assertEqual(settings["global/alarm_manager_constants"], "other=1,min_futurity=5000")
        self.assertNotIn("global/dumbtv_min_futurity", settings)

    def test_without_an_earlier_value_the_setting_goes(self):
        _, settings = self.undo({"global/alarm_manager_constants": "min_futurity=0",
                                 "secure/enabled_accessibility_services": GUARD})
        self.assertNotIn("global/alarm_manager_constants", settings)

    def test_the_doze_exemption_goes_too(self):
        commands, settings = self.undo({"secure/enabled_accessibility_services": GUARD})
        self.assertIn("dumpsys deviceidle whitelist -tv.dumbtv.home", commands)
        self.assertIn("appops set tv.dumbtv.home AUTO_START default", commands)
        self.assertNotIn(GUARD, settings.get("secure/enabled_accessibility_services", ""))

    def test_other_accessibility_services_are_kept(self):
        _, settings = self.undo({"secure/enabled_accessibility_services": f"example.reader/.Service:{GUARD}"})
        self.assertEqual(settings["secure/enabled_accessibility_services"], "example.reader/.Service")

    def test_android14_undoes_the_device_config_value(self):
        # Android 12+ keeps min_futurity in DeviceConfig. The global setting is undone as
        # well, because installs before that split wrote it on these TVs too.
        commands, settings = self.undo({"config/alarm_manager/min_futurity": "0",
                                        "global/alarm_manager_constants": "min_futurity=0",
                                        "secure/enabled_accessibility_services": GUARD}, sdk=34)
        self.assertNotIn("config/alarm_manager/min_futurity", settings)
        self.assertNotIn("global/alarm_manager_constants", settings)
        self.assertIn("device_config delete alarm_manager min_futurity", commands)

    def test_android14_puts_an_earlier_value_back(self):
        _, settings = self.undo({"config/alarm_manager/min_futurity": "0",
                                 "global/dumbtv_min_futurity": "2000",
                                 "secure/enabled_accessibility_services": GUARD}, sdk=34)
        self.assertEqual(settings["config/alarm_manager/min_futurity"], "2000")
        self.assertNotIn("global/dumbtv_min_futurity", settings)


if __name__ == "__main__":
    unittest.main()
