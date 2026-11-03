"""Keep-list validation in configure.sh, without ADB or a television.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ConfigureKeepTest(unittest.TestCase):
    def configure(self, *flags):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            env = dict(os.environ, CONF=str(conf))
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y", *flags],
                env=env, capture_output=True, text=True, timeout=30,
            )
            return result, conf.read_text() if conf.exists() else None

    def test_app_on_removal_list_is_kept(self):
        result, conf = self.configure("--keep", "in.startv.hotstar")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('KEEP_APPS="in.startv.hotstar"', conf)

    def test_amazon_package_is_kept(self):
        result, conf = self.configure("--keep", "com.amazon.tv.acr")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('KEEP_APPS="com.amazon.tv.acr"', conf)

    def test_app_on_no_list_is_refused_before_writing(self):
        result, conf = self.configure("--keep", "com.netflix.ninja")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("com.netflix.ninja", result.stderr)
        self.assertIsNone(conf)

    def test_defaults_remove_nothing_and_keep_alexa(self):
        result, conf = self.configure()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('REMOVE_EXTRA=""', conf)
        self.assertIn("ALEXA=1", conf)
        self.assertIn("PRIME=1", conf)
        self.assertIn("YOUTUBE=0", conf)

    def test_flags_answer_every_question(self):
        result, conf = self.configure(
            "--prime", "0", "--alexa", "0", "--launcher", "0",
            "--dnsfilter", "0", "--youtube", "1", "--system-dns", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        for line in ("PRIME=0", "ALEXA=0", "LAUNCHER=0", "DNSFILTER=0",
                     "YOUTUBE=1", "SYSTEM_DNS=1"):
            self.assertIn(line, conf)

    def test_a_yes_no_flag_takes_only_0_or_1(self):
        result, conf = self.configure("--youtube", "yes")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(conf)

    def test_named_app_is_recorded_for_removal(self):
        result, conf = self.configure("--remove", "in.startv.hotstar")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('REMOVE_EXTRA="in.startv.hotstar"', conf)

    def test_only_package_names_reach_the_config(self):
        # dumbtv.conf is sourced by every script: a quote breaks them all, and $(...) would run.
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "ran"
            for bad in ('com.netflix.ninja"', f"com.x$(touch {marker})", "Netflix"):
                with self.subTest(bad=bad):
                    result, conf = self.configure("--remove", bad)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("is not a package name", result.stderr)
                    self.assertIsNone(conf)
            self.assertFalse(marker.exists())

    def test_a_package_the_tv_needs_cannot_be_removed(self):
        result, conf = self.configure("--remove", "com.amazon.tv.launcher")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(conf)

    def test_extra_removal_can_be_kept(self):
        result, conf = self.configure(
            "--keep", "com.netflix.ninja", "--remove", "com.netflix.ninja")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_dns_upstream_is_saved_and_kept_on_the_next_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            env = dict(os.environ, CONF=str(conf))
            first = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y",
                 "--dns-upstream", "76.76.2.45 2606:1a40::45"],
                env=env, capture_output=True, text=True,
            )
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertIn('DNS_UPSTREAM="76.76.2.45 2606:1a40::45"', conf.read_text())
            second = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y"],
                env=env, capture_output=True, text=True,
            )
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertIn('DNS_UPSTREAM="76.76.2.45 2606:1a40::45"', conf.read_text())

    def test_dns_upstream_rejects_a_name(self):
        result, conf = self.configure("--dns-upstream", "dns.example")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(conf)

    def test_dns_upstream_without_a_value_is_refused(self):
        result, conf = self.configure("--dns-upstream")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('pass "" to clear', result.stderr)
        self.assertIsNone(conf)

    def test_dns_upstream_of_only_spaces_means_the_router(self):
        result, conf = self.configure("--dns-upstream", "  ")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DNS_UPSTREAM=""', conf)

    def test_dns_upstream_saved_without_quotes_is_kept(self):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            conf.write_text("DNS_UPSTREAM=9.9.9.9\n")
            result = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y"],
                env=dict(os.environ, CONF=str(conf)), capture_output=True, text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('DNS_UPSTREAM="9.9.9.9"', conf.read_text())

    def test_empty_dns_upstream_clears_a_saved_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "dumbtv.conf"
            env = dict(os.environ, CONF=str(conf))
            subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y",
                 "--dns-upstream", "76.76.2.45"],
                env=env, capture_output=True, text=True, check=True,
            )
            cleared = subprocess.run(
                ["bash", str(ROOT / "scripts" / "configure.sh"), "-y",
                 "--dns-upstream", ""],
                env=env, capture_output=True, text=True,
            )
            self.assertEqual(cleared.returncode, 0, cleared.stderr)
            self.assertIn('DNS_UPSTREAM=""', conf.read_text())


if __name__ == "__main__":
    unittest.main()
