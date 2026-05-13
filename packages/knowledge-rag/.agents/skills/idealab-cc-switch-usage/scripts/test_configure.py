#!/usr/bin/env python3
"""Unit tests for configure.py's cookie parsing.

Run: `python3 test_configure.py` or `python3 -m unittest test_configure`.
"""

import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from configure import extract_cookie_from_input  # noqa: E402

# Minimal cookie that passes the required-keys sanity check.
GOOD_COOKIE = (
    "cna=ABC; "
    "idea-lab_USER_COOKIE_V3=ST186.user-token-payload; "
    "idea-lab_SSO_TOKEN_V3=ST186.sso-token-payload"
)


class TestCookieParsing(unittest.TestCase):
    def test_raw_cookie_string(self):
        """Pasting just the cookie header value works."""
        self.assertEqual(extract_cookie_from_input(GOOD_COOKIE), GOOD_COOKIE)

    def test_curl_single_quoted_b(self):
        """Real-world case: DevTools 'Copy as cURL' uses -b '...'."""
        curl = f"curl 'https://aistudio.alibaba-inc.com/api/...' -b '{GOOD_COOKIE}' --data-raw '{{}}'"
        self.assertEqual(extract_cookie_from_input(curl), GOOD_COOKIE)

    def test_curl_double_quoted_cookie_long_form(self):
        """Some shells / Windows curl use --cookie \"...\"."""
        curl = f'curl "https://x" --cookie "{GOOD_COOKIE}"'
        self.assertEqual(extract_cookie_from_input(curl), GOOD_COOKIE)

    def test_curl_with_backslash_line_continuations(self):
        """The canonical multi-line form copied from DevTools."""
        curl = (
            "curl 'https://aistudio.alibaba-inc.com/api/...' \\\n"
            "  -H 'accept: application/json' \\\n"
            f"  -b '{GOOD_COOKIE}' \\\n"
            "  --data-raw '{}'"
        )
        self.assertEqual(extract_cookie_from_input(curl), GOOD_COOKIE)

    def test_strips_surrounding_whitespace_and_quotes(self):
        """Stray quotes around a raw cookie shouldn't poison it."""
        self.assertEqual(extract_cookie_from_input(f"   '{GOOD_COOKIE}'   "), GOOD_COOKIE)

    def test_empty_input_exits(self):
        with self.assertRaises(SystemExit) as cm:
            extract_cookie_from_input("   \n  ")
        self.assertEqual(cm.exception.code, 2)

    def test_missing_required_keys_exits(self):
        """A cookie without idea-lab_* keys is almost certainly the wrong cookie."""
        with self.assertRaises(SystemExit) as cm:
            extract_cookie_from_input("session=abc; csrf=xyz")
        self.assertEqual(cm.exception.code, 2)

    def test_curl_without_cookie_arg_exits(self):
        with self.assertRaises(SystemExit) as cm:
            extract_cookie_from_input("curl 'https://x' -H 'accept: application/json'")
        self.assertEqual(cm.exception.code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
