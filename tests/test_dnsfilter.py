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
routeUnderlyingDNS = false
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
            for name in ("DNS_UPSTREAM", "SYSTEM_DNS", "FILTER_AUTOSTART", "DNSFILTER", "PRIME"):
                env.pop(name, None)
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "dnsfilter.sh"), "-y", "--no-reboot"],
                env=env, capture_output=True, text=True, timeout=120,
            )
            pushed = tmp / "pushed" / "dnsfilter.conf"
            lines = pushed.read_text().splitlines() if pushed.exists() else None
            touched = [c for c in log.read_text().splitlines()
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

    def test_the_filter_may_start_itself_on_tcl(self):
        # TclAppBoot blocks the VPN service of an app without AUTO_START (#176).
        self.pushed_conf({"net.dns1": GATEWAY}, sdk=34)
        self.assertIn("appops set dnsfilter.android AUTO_START allow", self.touched)

    def test_no_bypass_on_fire_os_7_and_8(self):
        self.assertEqual(self.whitelist(28), ["androidAppWhiteList = <none>"])
        self.assertEqual(self.whitelist(30, "SYSTEM_DNS=1\n"), ["androidAppWhiteList = <none>"])


if __name__ == "__main__":
    unittest.main()
