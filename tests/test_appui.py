"""scripts/appui.sh, which drives an app's screen with TAB and OK, and backup_active.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
VIEWS = 'dnsField advancedConfigCheck enableAddFilter startBtn logOutput'


def run(script, views=VIEWS, backup='Backup Manager is enabled / provisioned / not pending init',
        failed_dumps=0, checked='', dialog=False, steal_to=''):
    with tempfile.TemporaryDirectory() as tmp:
        env = dict(os.environ, ADB=str(ROOT / 'tests' / 'fake_screen_adb.py'), SERIAL='test-tv',
                   FAKE_DIR=tmp, VIEWS=views, BACKUP_FIRST_LINE=backup,
                   FAILED_DUMPS=str(failed_dumps), CHECKED=checked,
                   DIALOG='1' if dialog else '0', STEAL_TO=steal_to)
        result = subprocess.run(
            ['bash', '-c', 'set -uo pipefail; source scripts/lib.sh; source scripts/appdata.sh; '
             'source scripts/appui.sh; ' + script + '; echo "rc=$?"'],
            cwd=ROOT, env=env, capture_output=True, text=True, timeout=120)
        log = (Path(tmp) / 'log').read_text().splitlines() if (Path(tmp) / 'log').exists() else []
    return result.stdout, log


class FocusTest(unittest.TestCase):
    def test_tab_reaches_the_view_and_ok_presses_it(self):
        out, log = run('ui_press startBtn')
        self.assertIn('rc=0', out)
        self.assertEqual(log.count('input keyevent KEYCODE_TAB'), 3)
        self.assertEqual(log[-1], 'input keyevent KEYCODE_DPAD_CENTER')
        self.assertFalse(any('input tap' in line for line in log))

    def test_a_view_that_is_not_on_screen_fails(self):
        out, log = run('ui_focus manuallyEditField')
        self.assertIn('rc=1', out)
        self.assertEqual(log.count('input keyevent KEYCODE_TAB'), 40)

    def test_tick_leaves_a_ticked_box_alone(self):
        out, log = run('ui_tick advancedConfigCheck && ui_tick advancedConfigCheck && ui_checked advancedConfigCheck')
        self.assertIn('rc=0', out)
        self.assertEqual(log.count('input keyevent KEYCODE_DPAD_CENTER'), 1)

    def test_a_busy_screen_is_read_again_before_any_key(self):
        # Four blind TABs would carry the focus past startBtn and round the loop again.
        out, log = run('ui_press startBtn', failed_dumps=4, dialog=True)
        self.assertIn('rc=0', out)
        self.assertEqual(log.count('input keyevent KEYCODE_TAB'), 3)

    def test_a_log_that_takes_the_focus_back_does_not_stop_the_tick(self):
        # #196: on a rerun the filter is live and dumbtv home is installed. Every uiautomator
        # dump rebinds Guard, Guard wakes the filter, and the filter's log takes the focus.
        out, log = run('ui_tick advancedConfigCheck', steal_to='logOutput')
        self.assertIn('rc=0', out)
        self.assertLess(log.count('input keyevent KEYCODE_TAB'), 40)

    def test_focus_is_read_without_uiautomator_when_the_activity_shows_the_view(self):
        _, log = run('ui_focus startBtn')
        self.assertFalse(any(line.startswith('uiautomator') for line in log))

    def test_an_unreadable_screen_presses_nothing(self):
        # A ticked box read as unticked would be pressed, and so unticked.
        out, log = run('ui_tick advancedConfigCheck', failed_dumps=99, checked='advancedConfigCheck')
        self.assertIn('rc=1', out)
        self.assertFalse(any(line.startswith('input keyevent') for line in log))

    def test_typed_spaces_survive_input_text(self):
        _, log = run('ui_type "routeIPs = 198.51.100.1; 2001:db8::1"')
        self.assertIn("input text 'routeIPs%s=%s198.51.100.1;%s2001:db8::1'", log)


class BackupTest(unittest.TestCase):
    def test_a_running_service_can_restore_even_when_backups_are_off(self):
        out, _ = run('backup_active', backup='Backup Manager is disabled / provisioned / not pending init')
        self.assertIn('rc=0', out)

    def test_an_inactive_service_cannot(self):
        out, _ = run('backup_active', backup='Inactive')
        self.assertIn('rc=1', out)

    def test_a_missing_service_cannot(self):
        out, _ = run('backup_active', backup="Can't find service: backup")
        self.assertIn('rc=1', out)


if __name__ == '__main__':
    unittest.main()
