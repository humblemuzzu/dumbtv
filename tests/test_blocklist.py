"""The DNS filter's list follows the owner's answers about Prime Video and Alexa.

Run: python3 -m unittest discover -s tests -v
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FEED = "# feed\napi.us-east-1.aiv-delivery.net\nmetrics.example-tracker.com\n"


def lib(script, **env):
    with tempfile.TemporaryDirectory() as tmp:
        feed = Path(tmp) / "feed.txt"
        feed.write_text(FEED)
        result = subprocess.run(
            ["bash", "-c", f'source "$1"; {script} "$2"', "bash",
             str(ROOT / "scripts" / "lib.sh"), str(feed)],
            env=dict(os.environ, **env), capture_output=True, text=True,
        )
    return result.stdout.split()


def check_feed(text):
    with tempfile.TemporaryDirectory() as tmp:
        feed = Path(tmp) / "feed.txt"
        feed.write_text(text)
        return subprocess.run(
            ["bash", "-c", 'source "$1"; check_feed "$2"', "bash",
             str(ROOT / "scripts" / "lib.sh"), str(feed)],
            capture_output=True, text=True,
        )


DOMAINS = "".join(f"t{i}.tracker-{i}.example.com\n" for i in range(60))


class FeedTest(unittest.TestCase):
    def test_a_normal_feed_passes(self):
        result = check_feed("# Title: Amazon\n# Number of entries: 61\n\n" + DOMAINS + "*.wild.example.net\n")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_windows_line_endings_pass_and_are_not_pushed(self):
        crlf = DOMAINS.replace("\n", "\r\n")
        self.assertEqual(check_feed(crlf).returncode, 0)
        with tempfile.TemporaryDirectory() as tmp:
            feed = Path(tmp) / "feed.txt"
            feed.write_text(crlf)
            out = subprocess.run(
                ["bash", "-c", 'source "$1"; compose_blocklist "$2"', "bash",
                 str(ROOT / "scripts" / "lib.sh"), str(feed)],
                env=dict(os.environ, PRIME="1", ALEXA="1"), capture_output=True, text=True).stdout
        self.assertNotIn("\r", out)

    def test_an_error_page_is_refused(self):
        result = check_feed("<!DOCTYPE html>\n<html><body>Sign in to the Wi-Fi</body></html>\n" + DOMAINS)
        self.assertEqual(result.returncode, 1)
        self.assertIn("not a domain", result.stderr)

    def test_a_cut_off_feed_is_refused(self):
        result = check_feed("".join(f"t{i}.example.com\n" for i in range(10)))
        self.assertEqual(result.returncode, 1)
        self.assertIn("only 10 entries", result.stderr)

    def test_an_empty_feed_is_refused(self):
        self.assertEqual(check_feed("").returncode, 1)


class BlocklistTest(unittest.TestCase):
    def test_defaults_keep_prime_and_alexa_working(self):
        hosts = lib("compose_blocklist", PRIME="1", ALEXA="1")
        self.assertIn("metrics.example-tracker.com", hosts)
        self.assertIn("softwareupdates.amazon.com", hosts)
        self.assertNotIn("api.us-east-1.aiv-delivery.net", hosts)
        self.assertNotIn("device-messaging-na.amazon.com", hosts)
        self.assertNotIn("api.amazonalexa.com", hosts)

    def test_no_prime_no_alexa_blocks_both(self):
        hosts = lib("compose_blocklist", PRIME="0", ALEXA="0")
        self.assertIn("api.us-east-1.aiv-delivery.net", hosts)
        self.assertIn("device-messaging-na.amazon.com", hosts)
        self.assertIn("api.amazonalexa.com", hosts)

    def test_verify_expects_only_what_was_blocked(self):
        default = lib("expected_blocked", PRIME="1", ALEXA="1")
        self.assertNotIn("api.amazonalexa.com", default)
        self.assertNotIn("device-messaging-na.amazon.com", default)
        strict = lib("expected_blocked", PRIME="0", ALEXA="0")
        self.assertIn("api.amazonalexa.com", strict)
        self.assertIn("device-messaging-na.amazon.com", strict)


if __name__ == "__main__":
    unittest.main()
