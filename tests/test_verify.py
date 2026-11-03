"""Regression checks for enabled-but-unbound services, entirely off-device."""
import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LABEL = 'Service[label=dumbtv home button, feedbackType[FEEDBACK_GENERIC]]'
# The launcher release this repo ships. The launcher on a TV can be older: up to
# 1.2 let the TV sleep while an HDMI picture was up.
RELEASE = re.search(r'^VERSION=(\S+)', (ROOT / 'launcher/version.env').read_text(), re.M).group(1)


class VerifyTests(unittest.TestCase):
    BLOCKED = ['softwareupdates.amazon.com', 'arcus-uswest.amazon.com',
               'unagi-zaz.amazon.com', 'unagi-na.amazon.com']
    ALLOWED = ['www.youtube.com', 'i.ytimg.com',
               'appstore-tv-prod-na.amazon.com', 'm.media-amazon.com']

    def run_verify(self, dump, *, component='tv.dumbtv.home/.Guard', enabled='1',
                   user='0', launcher='1', dump_code=0, sdk='34', futurity='0',
                   alarms='min_futurity=0',
                   landed='tv.dumbtv.home/.Home',
                   away='com.amazon.tv.launcher/.ui.MainSettingsActivity',
                   dnsfilter='0', launcher_package=None, extra=None, keep=''):
        # Android 14's shape (the fake reports sdk 34): a per-task topResumedActivity=,
        # and the top-level ResumedActivity: after the task list.
        def resumed(app):
            return ('  * Task{1 #5 type=standard A=10050:' + app.split('/')[0] + '}\n'
                    '      topResumedActivity=ActivityRecord{1 u0 ' + app + ' t5}\n'
                    '  ResumedActivity: ActivityRecord{1 u0 ' + app + ' t5}')
        replies = {
            'getprop ro.build.version.sdk': {'out': sdk},
            # Asked three times: what is in front now, what the check moved to, and
            # where HOME landed.
            'dumpsys activity activities': [
                {'out': resumed('example.player/.Main')},
                {'out': resumed(away)},
                {'out': resumed(landed)},
            ],
            'dumpsys power': {'out': 'mWakefulness=Awake'},
            'device_config get alarm_manager min_futurity': {'out': futurity},
            'settings get global alarm_manager_constants': {'out': alarms},
            'am get-current-user': {'out': user},
            f'settings --user {user} get secure enabled_accessibility_services': {'out': component},
            f'settings --user {user} get secure accessibility_enabled': {'out': enabled},
            'dumpsys accessibility': {'out': dump, 'code': dump_code},
            'pm list packages -d': {'out': ''},
            'dumpsys package tv.dumbtv.home': {
                'out': f'    versionName={RELEASE}' if launcher_package is None else launcher_package},
            'pm list packages': {'out': ''},
            'cmd netpolicy get restrict-background': {'out': 'Restrict background status: enabled'},
            'settings list secure; settings list global': {'out': self.applied_settings()},
            'settings get secure screensaver_components': {'out': 'null'},
            'am start -a android.intent.action.MAIN -c android.intent.category.LEANBACK_SETTINGS': {'out': ''},
            'input keyevent KEYCODE_HOME': {'out': ''},
        }
        if dnsfilter == '1':
            for h in self.BLOCKED:
                replies[f'ping -c1 -W2 {h}'] = {'out': f'ping: unknown host {h}'}
            for h in self.ALLOWED:
                replies[f'ping -c1 -W2 {h}'] = {'out': f'PING {h} (142.250.1.1) 56(84) bytes of data.'}
            replies.update({
                'pm path dnsfilter.android': {'out': 'package:/data/app/dnsfilter.android/base.apk'},
                'dumpsys package dnsfilter.android': {'out': '    userId=10251'},
                'dumpsys connectivity': {'out': 'NetworkAgentInfo{ ni{[type: VPN[], state: CONNECTED]} nc{[ Uid: 10251 OwnerUid: 10251 ]} }'},
                'ip addr show': {'out': '18: tun0: <POINTOPOINT,UP,LOWER_UP> mtu 1280'},
                'settings get secure always_on_vpn_app': {'out': 'null'},
                'grep -c . /sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/FILTERHOSTS.TXT 2>/dev/null': {'out': '415'},
            })
        replies.update(extra or {})
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            data, log = tmp / 'responses.json', tmp / 'calls.jsonl'
            data.write_text(json.dumps(replies))
            env = dict(os.environ, ADB=str(ROOT / 'tests/fake_verify_adb.py'),
                       SERIAL='test-tv', CONF=str(tmp / 'absent.conf'),
                       DNSFILTER=dnsfilter, LAUNCHER=launcher, KEEP_APPS=keep, FAKE_ADB_LOG=str(log),
                       FAKE_ADB_RESPONSES=str(data))
            result = subprocess.run(['bash', str(ROOT / 'scripts/verify.sh')],
                                    env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
        self.assertNotIn('unexpected command', result.stdout + result.stderr)
        # The fake refuses any command it has no reply for, so nothing else reaches a TV.
        return result, calls

    @staticmethod
    def applied_settings(**changed):
        """`settings list` output for a TV where debloat.sh applied every privacy setting."""
        rows = []
        for line in (ROOT / 'data/fireos/settings.txt').read_text().splitlines():
            line = line.split('#')[0].split()
            if len(line) == 3:
                rows.append(f'{line[1]}={changed.get(line[1], line[2])}')
        return '\n'.join(rows)

    @staticmethod
    def modern(bound, user=0):
        return f'User state[\n attributes:{{id={user}, flags=0}}\n Bound services:{{{bound}}}\n Enabled services:{{tv.dumbtv.home/.Guard}}\n Binding services:{{}}\n]\n'

    def test_bound_android14_while_another_app_is_foreground(self):
        result, _ = self.run_verify(self.modern(LABEL))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('bound, front is example.player/.Main', result.stdout)

    def test_a_disabled_screensaver_is_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), extra={
            'pm list packages -d': {'out': 'package:com.amazon.ftv.screensaver\npackage:com.amazon.tv.acr'},
            'settings get secure screensaver_components':
                {'out': 'com.amazon.ftv.screensaver/.app.services.ScreensaverService'},
        })
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('2 disabled', result.stdout)
        self.assertIn('com.amazon.ftv.screensaver is disabled, so the TV sleeps', result.stdout)

    def test_privacy_settings_as_set(self):
        result, _ = self.run_verify(self.modern(LABEL))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertRegex(result.stdout, r'ok +settings +\d+ privacy settings as set')
        self.assertNotIn('enabled again', result.stdout)

    def test_a_setting_an_update_reset_is_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), extra={
            'settings list secure; settings list global':
                {'out': self.applied_settings(limit_ad_tracking='0')},
        })
        self.assertIn("back to Amazon's values: limit_ad_tracking=0", result.stdout)

    def test_a_package_an_update_enabled_again_is_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), extra={
            'pm list packages': {'out': 'package:com.amazon.tv.acr\npackage:com.netflix.ninja'},
        })
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('enabled again: com.amazon.tv.acr;', result.stdout)

    def test_a_package_the_owner_kept_is_not_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), keep='com.amazon.tv.acr', extra={
            'pm list packages': {'out': 'package:com.amazon.tv.acr'},
        })
        self.assertNotIn('enabled again', result.stdout)

    def test_data_saver_on_after_lockdown(self):
        result, _ = self.run_verify(self.modern(LABEL))
        self.assertRegex(result.stdout, r'ok +data-saver +on')

    def test_data_saver_an_update_turned_off_is_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), extra={
            'cmd netpolicy get restrict-background': {'out': 'Restrict background status: disabled'},
        })
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('off, so protected packages use data freely; run lockdown.sh', result.stdout)

    def test_a_package_still_disabled_is_not_flagged(self):
        result, _ = self.run_verify(self.modern(LABEL), extra={
            'pm list packages': {'out': 'package:com.amazon.tv.acr'},
            'pm list packages -d': {'out': 'package:com.amazon.tv.acr'},
        })
        self.assertNotIn('enabled again', result.stdout)

    def test_bound_android9_format(self):
        dump = f'User state[attributes:{{id=0, currentUser=true}}\n services:{{{LABEL}}}]'
        result, _ = self.run_verify(dump)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_bound_android9_class_name_after_update(self):
        # Seen on a Fire OS 7 TV right after adb install -r: the label is not loaded yet.
        by_class = LABEL.replace('dumbtv home button', 'tv.dumbtv.home.Guard')
        dump = f'User state[attributes:{{id=0, currentUser=true}}\n services:{{{by_class}}}]'
        result, _ = self.run_verify(dump)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_other_class_name_does_not_pass(self):
        other = LABEL.replace('dumbtv home button', 'tv.dumbtv.home.GuardX')
        dump = f'User state[attributes:{{id=0, currentUser=true}}\n services:{{{other}}}]'
        result, _ = self.run_verify(dump)
        self.assertEqual(result.returncode, 1)

    def test_enabled_but_unbound_is_failure(self):
        result, _ = self.run_verify(self.modern(''))
        self.assertEqual(result.returncode, 1)
        self.assertIn('enabled but not bound', result.stdout)

    def test_label_outside_bound_section_does_not_pass(self):
        result, _ = self.run_verify(self.modern('') + 'Binding services:{' + LABEL + '}')
        self.assertEqual(result.returncode, 1)

    def test_other_user_bound_does_not_pass(self):
        result, _ = self.run_verify(self.modern(LABEL, 10) + self.modern(''))
        self.assertEqual(result.returncode, 1)

    def test_current_secondary_user_and_full_component(self):
        result, _ = self.run_verify(self.modern('', 0) + self.modern(LABEL, 10), user='10',
                                    component='example.reader/.Service:tv.dumbtv.home/tv.dumbtv.home.Guard')
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_similar_labels_or_components_do_not_pass(self):
        for dump, component in [(self.modern(LABEL.replace('button,', 'button fake,')), 'tv.dumbtv.home/.Guard'),
                                (self.modern(LABEL), 'tv.dumbtv.home.fake/.Guard')]:
            with self.subTest(component=component):
                result, _ = self.run_verify(dump, component=component)
                self.assertEqual(result.returncode, 1)

    def test_global_accessibility_disabled(self):
        result, _ = self.run_verify(self.modern(LABEL), enabled='0')
        self.assertEqual(result.returncode, 1)

    def test_failed_empty_unknown_or_truncated_dump(self):
        for dump, code in [('', 0), ('Permission denied', 0), (self.modern(LABEL), 1),
                           ('User state[\n attributes:{id=0, flags=0}\n Bound services:{' + LABEL, 0)]:
            with self.subTest(dump=dump):
                result, _ = self.run_verify(dump, dump_code=code)
                self.assertEqual(result.returncode, 1)
                self.assertIn('cannot verify accessibility binding', result.stdout)

    def test_unreadable_user_is_unknown(self):
        result, _ = self.run_verify(self.modern(LABEL), user='Error')
        self.assertEqual(result.returncode, 1)

    def test_launcher_opt_out_skips_binding_queries(self):
        result, calls = self.run_verify('', launcher='0')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertFalse(any('accessibility' in ' '.join(c) for c in calls))

    def test_the_launcher_release_on_the_tv_is_reported(self):
        result, _ = self.run_verify(self.modern(LABEL))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('ok         launcher', result.stdout)
        self.assertIn(RELEASE, result.stdout)

    def test_an_old_launcher_is_a_failure(self):
        # Releases up to 1.2 let the TV sleep while an HDMI picture was up, and
        # nothing else on the TV says so.
        result, _ = self.run_verify(self.modern(LABEL), launcher_package='    versionName=1.2')
        self.assertEqual(result.returncode, 1)
        self.assertIn('FAIL       launcher', result.stdout)
        self.assertIn('run ./scripts/launcher.sh -y', result.stdout)

    def test_a_launcher_newer_than_this_checkout_passes(self):
        result, _ = self.run_verify(self.modern(LABEL), launcher_package='    versionName=99.0')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('newer than', result.stdout)

    def test_a_missing_launcher_is_skipped(self):
        result, _ = self.run_verify(self.modern(LABEL),
                                    launcher_package='Unable to find package: tv.dumbtv.home')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('skip       launcher', result.stdout)

    def test_speed_requirement_is_preserved(self):
        result, _ = self.run_verify(self.modern(LABEL), futurity='null')
        self.assertEqual(result.returncode, 1)
        self.assertIn('min_futurity=0 missing', result.stdout)

    def test_android14_ignores_the_android11_alarm_setting(self):
        # Android 12+ reads min_futurity from DeviceConfig only (issue #57): the global
        # setting an older install wrote there must not pass the check.
        result, _ = self.run_verify(self.modern(LABEL), futurity='null', alarms='min_futurity=0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('min_futurity=0 missing', result.stdout)

    def test_android12_is_the_first_to_read_device_config(self):
        result, _ = self.run_verify(self.modern(LABEL), sdk='31', futurity='null', alarms='min_futurity=0')
        self.assertEqual(result.returncode, 1)
        self.assertIn('min_futurity=0 missing', result.stdout)

    def test_android11_reads_the_global_alarm_setting(self):
        result, _ = self.run_verify(self.modern(LABEL), sdk='30', futurity='null',
                                    alarms='other=1,min_futurity=0')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('ok         alarm', result.stdout)

    def test_a_futurity_that_only_starts_with_zero_is_not_zero(self):
        result, _ = self.run_verify(self.modern(LABEL), sdk='30', alarms='min_futurity=05000')
        self.assertEqual(result.returncode, 1)

    def test_home_key_proves_the_takeover(self):
        result, _ = self.run_verify(self.modern(LABEL))
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('ok         home-key', result.stdout)
        self.assertIn('HOME lands on tv.dumbtv.home/.Home', result.stdout)

    def test_home_key_on_another_launcher_is_a_failure(self):
        # The service can be bound and the alarm can fire while HOME stays on
        # Amazon's launcher, so the key itself is the only proof.
        result, _ = self.run_verify(self.modern(LABEL),
                                    landed='com.amazon.tv.launcher/.ui.HomeActivity_vNext')
        self.assertEqual(result.returncode, 1)
        self.assertIn('FAIL       home-key', result.stdout)

    def test_home_key_is_skipped_when_the_home_screen_cannot_be_left(self):
        # Nothing to prove: the check never got away from our own home.
        result, _ = self.run_verify(self.modern(LABEL), away='tv.dumbtv.home/.Home')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('skip       home-key', result.stdout)

    def test_nxdomain_from_a_router_is_blocked(self):
        # Blocky and Unbound answer NXDOMAIN for a denylisted host where
        # personalDNSfilter answers 0.0.0.0; both mean the lookup was filtered.
        result, _ = self.run_verify(self.modern(LABEL), dnsfilter='1')
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn('blocked (NXDOMAIN)', result.stdout)
        self.assertIn('ok         vpn', result.stdout)

    def test_nxdomain_from_a_dead_resolver_is_not_blocked(self):
        # If nothing resolves, "unknown host" proves the resolver is down, not
        # that the blocklist works, so it must not pass as blocked.
        dead = {f'ping -c1 -W2 {h}': {'out': f'ping: unknown host {h}'}
                for h in self.BLOCKED + self.ALLOWED}
        result, _ = self.run_verify(self.modern(LABEL), dnsfilter='1', extra=dead)
        self.assertEqual(result.returncode, 1)
        self.assertIn('no answer from the resolver', result.stdout)

    def test_public_address_is_still_not_blocked(self):
        resolving = {f'ping -c1 -W2 {h}': {'out': f'PING {h} (52.1.2.3) 56(84) bytes of data.'}
                     for h in self.BLOCKED}
        result, _ = self.run_verify(self.modern(LABEL), dnsfilter='1', extra=resolving)
        self.assertEqual(result.returncode, 1)
        self.assertIn('NOT BLOCKED', result.stdout)

    def test_another_vpns_tunnel_is_not_the_filter(self):
        # The owner's Surfshark tunnel was reported as personalDNSfilter before.
        result, _ = self.run_verify(self.modern(LABEL), dnsfilter='1',
                                    extra={'pm path dnsfilter.android': {'out': ''}})
        self.assertEqual(result.returncode, 1)
        self.assertIn('FAIL       vpn', result.stdout)
        self.assertIn('personalDNSfilter is not installed', result.stdout)
        self.assertNotIn('ok         vpn', result.stdout)


if __name__ == '__main__':
    unittest.main()
