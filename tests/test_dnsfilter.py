"""dnsfilter.sh against a pretend Fire TV: which DNS servers it puts inside the tunnel.

Run: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GATEWAY = "198.51.100.1"

# Only the lines dnsfilter.sh patches and checks; the real template has many more.
TEMPLATE = """filterAutoUpdateURL = https://example.org/list.txt
previousAutoUpdateURL = <none>
detectDNS = true
fallbackDNS = 203.0.113.9::53::UDP
routeIPs =
routeUnderlyingDNS = true
showInitialInfoPopUp = true
reloadIntervalDays = 7
filterHostsFile = FILTERHOSTS.TXT
androidAppWhiteList = com.android.vending
AUTOSTART = true
"""

# A feed the size of HaGeZi's: dnsfilter.sh refuses one with fewer than 50 entries.
FEED = "# list\ndevice-metrics-us.amazon.com\n" + "".join(
    f"t{i}.tracker.example.com\n" for i in range(60))

# Stands in for curl: answers each download with the file named like the URL's last part.
CURL = """#!/usr/bin/env bash
while [[ $# -gt 1 ]]; do [[ $1 == -o ]] && { out=$2; shift; }; shift; done
cp "$FAKE_CURL_DIR/${1##*/}" "$out"
"""


class RouteTest(unittest.TestCase):
    def run_filter(self, props, sdk=28, conf="", feed=FEED):
        """Runs dnsfilter.sh; returns the result and the dnsfilter.conf it pushed, if any."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            files = tmp / "downloads"
            files.mkdir()
            (files / "dnsfilter.conf").write_text(TEMPLATE)
            (files / "native.amazon-onlydomains.txt").write_text(feed)
            bin_dir = tmp / "bin"
            bin_dir.mkdir()
            (bin_dir / "curl").write_text(CURL)
            (bin_dir / "curl").chmod(0o755)
            state = tmp / "state.json"
            state.write_text(json.dumps({
                "packages": {"dnsfilter.android": {"system": False, "enabled": True}},
                "versions": {"dnsfilter.android": 1506103},
                "routes": f"default via {GATEWAY} dev wlan0 table 1003 proto static",
                "props": props,
                "sdk": sdk,
                "pushed": str(tmp / "pushed"),
            }))
            log = tmp / "commands.log"
            log.touch()
            (tmp / "dumbtv.conf").write_text(conf)
            env = dict(os.environ, ADB=str(ROOT / "tests" / "fake_tv.py"), ADB_SERIAL="test-tv",
                       FAKE_TV_STATE=str(state), FAKE_TV_LOG=str(log), FAKE_CURL_DIR=str(files),
                       CONF=str(tmp / "dumbtv.conf"), REPORT=str(tmp / "report"),
                       PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
            for name in ("DNS_UPSTREAM", "SYSTEM_DNS", "FILTER_AUTOSTART", "FILTER_ALWAYS_ON",
                         "DNSFILTER", "PRIME"):
                env.pop(name, None)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "dnsfilter.sh"), "-y", "--no-reboot"],
                env=env, capture_output=True, text=True, timeout=120,
            )
            pushed = tmp / "pushed" / "dnsfilter.conf"
            lines = pushed.read_text().splitlines() if pushed.exists() else None
            self.commands = log.read_text().splitlines()
            touched = [c for c in self.commands
                       if c.startswith(("pm ", "appops set", "settings put", "am start"))]
        self.touched = touched
        return result, lines

    def pushed_conf(self, props, sdk=28, conf=""):
        result, lines = self.run_filter(props, sdk=sdk, conf=conf)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return lines

    def route_ips(self, props):
        return [line for line in self.pushed_conf(props) if line.startswith("routeIPs")]

    def whitelist(self, sdk, conf=""):
        lines = self.pushed_conf({"net.dns1": GATEWAY}, sdk=sdk, conf=conf)
        return [line for line in lines if line.startswith("androidAppWhiteList")]

    def test_a_sign_in_page_instead_of_the_feed_changes_nothing(self):
        result, conf = self.run_filter({"net.dns1": GATEWAY},
                                       feed="<html><body>Sign in to the Wi-Fi</body></html>\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("does not look right", result.stderr)
        self.assertIsNone(conf)
        self.assertEqual(self.touched, [])

    def test_ipv6_and_extra_dns_servers_go_inside_the_tunnel(self):
        # A router that hands out an IPv6 resolver by router advertisement, plus a public
        # resolver in net.dns2: lookups to either would otherwise skip the filter.
        routes = self.route_ips({
            "net.dns1": "2001:db8::1",
            "net.dns2": "203.0.113.53",
            "net.dns3": GATEWAY,
            "net.dns4": "fd00::53",
        })
        self.assertEqual(routes, [f"routeIPs = {GATEWAY}; 2001:db8::1; 203.0.113.53; fd00::53"])

    def test_gateway_alone_when_the_tv_knows_no_other_server(self):
        routes = self.route_ips({"net.dns1": GATEWAY, "net.dns2": "127.0.0.1", "net.dns3": "::1",
                                 "net.dns4": "not-an-address"})
        self.assertEqual(routes, [f"routeIPs = {GATEWAY}"])

    def test_prime_bypasses_the_tunnel_on_fire_os_6(self):
        # Android 7.1 gives a DNS-only VPN no INTERNET capability, and Prime will not play.
        self.assertEqual(self.whitelist(25),
                         ["androidAppWhiteList = com.amazon.firebat,com.amazon.avod"])

    def test_prime_bypass_keeps_the_system_dns_opt_in(self):
        self.assertEqual(self.whitelist(25, "SYSTEM_DNS=1\n"),
                         ["androidAppWhiteList = android,com.amazon.firebat,com.amazon.avod"])

    def test_no_bypass_when_prime_is_blocked(self):
        self.assertEqual(self.whitelist(25, "PRIME=0\n"), ["androidAppWhiteList = <none>"])

    def test_the_download_list_is_empty_not_commented_out(self):
        # The app merges a config with its defaults when it sees a new version, keeping only the
        # keys present; a commented-out URL came back as its own lists (#207).
        lines = self.pushed_conf({"net.dns1": GATEWAY}, sdk=25)
        self.assertIn("filterAutoUpdateURL = ", lines)
        self.assertIn("previousAutoUpdateURL = ", lines)
        self.assertFalse([line for line in lines if line.startswith("#filterAutoUpdateURL")])

    def test_the_vpn_is_not_rebuilt_when_the_networks_dns_changes(self):
        # On, the app rebuilds its VPN as the TV's DNS servers change during boot, and a rebuild
        # that reused tun0 left Fire OS 7 routing the VPN through the old interface's table.
        lines = self.pushed_conf({"net.dns1": GATEWAY}, sdk=28)
        self.assertIn("routeUnderlyingDNS = false", lines)

    def test_the_app_is_stopped_only_after_its_default_config_is_complete(self):
        self.pushed_conf({"net.dns1": GATEWAY}, sdk=25)
        wait = "test -f /sdcard/Android/data/dnsfilter.android/files/PersonalDNSFilter/VERSION.TXT"
        self.assertIn(wait, self.commands)
        self.assertLess(self.commands.index(wait), self.commands.index("am force-stop dnsfilter.android"))

    def test_the_filter_may_start_itself_on_tcl(self):
        # TclAppBoot blocks the VPN service of an app without AUTO_START (#176).
        self.pushed_conf({"net.dns1": GATEWAY}, sdk=34)
        self.assertIn("appops set dnsfilter.android AUTO_START allow", self.touched)

    def test_always_on_is_set_by_default(self):
        self.pushed_conf({"net.dns1": GATEWAY}, sdk=28)
        self.assertIn("settings put secure always_on_vpn_app dnsfilter.android", self.touched)

    def test_always_on_can_be_left_to_the_apps_own_start(self):
        # An AFTKRT whose system-started VPN never carried a lookup (#225, #226).
        self.pushed_conf({"net.dns1": GATEWAY}, sdk=28, conf="FILTER_ALWAYS_ON=0\n")
        self.assertIn("settings delete secure always_on_vpn_app", self.commands)
        self.assertFalse([c for c in self.touched if "always_on_vpn_app" in c])

    def test_both_starts_off_is_refused_before_any_change(self):
        result, conf = self.run_filter({"net.dns1": GATEWAY}, sdk=28,
                                       conf="FILTER_ALWAYS_ON=0\nFILTER_AUTOSTART=0\n")
        self.assertEqual(result.returncode, 1)
        self.assertIn("nothing to start the filter", result.stderr)
        self.assertIsNone(conf)
        self.assertEqual(self.touched, [])

    def test_no_bypass_on_fire_os_7_and_8_by_default(self):
        self.assertEqual(self.whitelist(28), ["androidAppWhiteList = <none>"])
        self.assertEqual(self.whitelist(30), ["androidAppWhiteList = <none>"])

    def test_the_clock_opt_in_is_honoured_on_fire_os_8_but_not_7(self):
        # A Fire OS 8 stick never set its clock behind the filter (#196); Fire OS 7 does.
        self.assertEqual(self.whitelist(30, "SYSTEM_DNS=1\n"), ["androidAppWhiteList = android"])
        self.assertEqual(self.whitelist(28, "SYSTEM_DNS=1\n"), ["androidAppWhiteList = <none>"])


if __name__ == "__main__":
    unittest.main()
