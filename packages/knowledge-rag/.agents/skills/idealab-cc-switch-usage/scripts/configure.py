#!/usr/bin/env python3
"""
Configure cc-switch usage detection for the Idea LAB (Alibaba aistudio) provider.

Cookie is always read from stdin to avoid massive shell args / escaping headaches.

Subcommands:
  setup     write a fresh usage_script (cookie inlined into the JS)
  refresh   update only the inlined Cookie value, keep everything else
  delete    remove the usage_script field entirely
  status    print current usage_script status for the provider
  verify    POST to the endpoint with the supplied cookie, no DB writes

Cookie input formats accepted on stdin:
  - raw cookie header value: "k1=v1; k2=v2; ..."
  - full curl command (the cookie is parsed from -b / --cookie)

Exit codes: 0 ok, 2 bad input, 3 provider not found, 4 verify failed, 5 db error.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

DEFAULT_DB = Path.home() / ".cc-switch" / "cc-switch.db"
DEFAULT_PROVIDER_NAME = "Idea LAB"
USAGE_ENDPOINT = "https://aistudio.alibaba-inc.com/api/ailab/ak/teamapi/getOrCreate"
USAGE_BODY = '{"teamCode":"API_TEAM_CODE_99"}'

# JS executed by cc-switch's QuickJS sandbox. Must evaluate to an object with
# `request` and `extractor`. Cookie is inlined as a JS string literal — the
# `__COOKIE_JSON__` placeholder is replaced with json.dumps(cookie).
SCRIPT_TEMPLATE = r"""({
  request: {
    url: "https://aistudio.alibaba-inc.com/api/ailab/ak/teamapi/getOrCreate",
    method: "POST",
    headers: {
      "Accept": "application/json",
      "Content-Type": "application/json;charset=UTF-8",
      "Origin": "https://aistudio.alibaba-inc.com",
      "Referer": "https://aistudio.alibaba-inc.com/",
      "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
      "Cookie": __COOKIE_JSON__
    },
    body: JSON.stringify({ teamCode: "API_TEAM_CODE_99" })
  },
  extractor: function(response) {
    if (!response || !response.success || !response.data) {
      return {
        isValid: false,
        invalidMessage: (response && response.message) || "查询失败,请检查 Cookie 是否过期"
      };
    }
    // daily quota resets at local-tz midnight; show "Xh Ym 后重置" for context
    var resetExtra;
    try {
      var now = new Date();
      var next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
      var totalMin = Math.max(0, Math.floor((next.getTime() - now.getTime()) / 60000));
      var h = Math.floor(totalMin / 60);
      var m = totalMin % 60;
      resetExtra = h > 0 ? (h + "h " + m + "m 后重置") : (m + "m 后重置");
    } catch (e) {
      resetExtra = "次日 00:00 重置";
    }
    var d = response.data;
    var planName = d.teamName || "Idealab";
    var calls = Number(d.todayUsedCount || 0);
    var callLimit = Number(d.dailyCallLimit || 0);
    var amount = Number(d.todayAmountCost || 0);
    var amountLimit = Number(d.dailyAmountLimit || 0);
    return [
      {
        planName: planName + " · 金额",
        used: amount,
        total: amountLimit,
        remaining: Math.max(0, amountLimit - amount),
        unit: "CNY",
        extra: resetExtra
      },
      {
        planName: planName + " · 次数",
        used: calls,
        total: callLimit,
        remaining: Math.max(0, callLimit - calls),
        unit: "calls",
        extra: resetExtra
      }
    ];
  }
})"""


def die(code: int, msg: str) -> "None":
    print(msg, file=sys.stderr)
    sys.exit(code)


# ---------- cookie parsing ----------

# Cookies the idealab usage endpoint actually relies on. If at least one of
# these is present the input passes our sanity check.
REQUIRED_COOKIE_KEYS = ("idea-lab_USER_COOKIE_V3", "idea-lab_SSO_TOKEN_V3")


def extract_cookie_from_input(blob: str) -> str:
    """Accept either a raw cookie string or a full curl command."""
    blob = blob.strip()
    if not blob:
        die(2, "stdin was empty; pipe a cookie or a curl command")

    # If it looks like curl, pull the value of -b / --cookie. We tolerate both
    # quoted and unquoted forms and curl's line continuations.
    if "curl " in blob or blob.lstrip().startswith("curl"):
        joined = re.sub(r"\\\s*\n", " ", blob)
        m = re.search(r"(?:-b|--cookie)\s+(?:'([^']*)'|\"([^\"]*)\"|(\S+))", joined)
        if not m:
            die(2, "curl command did not contain a -b / --cookie argument")
        cookie = next(g for g in m.groups() if g is not None)
    else:
        cookie = blob

    cookie = cookie.strip().strip("'\"")
    if not any(key in cookie for key in REQUIRED_COOKIE_KEYS):
        die(
            2,
            "cookie is missing idea-lab_USER_COOKIE_V3 / idea-lab_SSO_TOKEN_V3; "
            "are you sure it's the right cookie?",
        )
    return cookie


# ---------- endpoint verification ----------

def verify_cookie(cookie: str, timeout: float = 10.0) -> dict:
    """POST to the usage endpoint. Returns parsed JSON, exits on failure."""
    req = urllib.request.Request(
        USAGE_ENDPOINT,
        data=USAGE_BODY.encode("utf-8"),
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json;charset=UTF-8",
            "Origin": "https://aistudio.alibaba-inc.com",
            "Referer": "https://aistudio.alibaba-inc.com/",
            "User-Agent": (
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
            ),
            "Cookie": cookie,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        die(4, f"network error verifying cookie: {e}")
    except json.JSONDecodeError as e:
        die(4, f"endpoint returned non-JSON; cookie likely rejected: {e}")

    if not payload.get("success") or not payload.get("data"):
        die(4, f"endpoint rejected cookie: {payload.get('message') or payload}")
    return payload["data"]


# ---------- DB helpers ----------

def find_provider(conn: sqlite3.Connection, hint: str, app_type: str) -> tuple[str, str, dict]:
    """Return (id, name, meta_dict). Hint matches by id (exact) or name (LIKE)."""
    row = conn.execute(
        "SELECT id, name, meta FROM providers WHERE id=? AND app_type=?",
        (hint, app_type),
    ).fetchone()
    if row:
        return row[0], row[1], json.loads(row[2])

    rows = conn.execute(
        "SELECT id, name, meta FROM providers WHERE app_type=? AND name LIKE ?",
        (app_type, f"%{hint}%"),
    ).fetchall()
    if not rows:
        die(3, f"no {app_type} provider matched '{hint}'")
    if len(rows) > 1:
        listing = "\n  ".join(f"{r[0]}  {r[1]}" for r in rows)
        die(
            3,
            f"multiple providers match '{hint}'; pass --provider-id:\n  {listing}",
        )
    return rows[0][0], rows[0][1], json.loads(rows[0][2])


def write_meta(conn: sqlite3.Connection, provider_id: str, app_type: str, meta: dict) -> None:
    conn.execute(
        "UPDATE providers SET meta=? WHERE id=? AND app_type=?",
        (json.dumps(meta, ensure_ascii=False), provider_id, app_type),
    )
    conn.commit()


def build_script_code(cookie: str) -> str:
    return SCRIPT_TEMPLATE.replace("__COOKIE_JSON__", json.dumps(cookie, ensure_ascii=False))


# ---------- subcommands ----------

def cmd_setup(args, conn, cookie: str, verified: Optional[dict]) -> None:
    pid, name, meta = find_provider(conn, args.provider, args.app_type)
    if "usage_script" in meta and not args.force:
        die(
            2,
            f"'{name}' already has usage_script; use 'refresh' to update cookie "
            f"or pass --force to overwrite",
        )
    meta["usage_script"] = {
        "enabled": True,
        "language": "javascript",
        "code": build_script_code(cookie),
        "timeout": 10,
        "templateType": "custom",
        "apiKey": None,
        "autoQueryInterval": 5,
    }
    write_meta(conn, pid, args.app_type, meta)
    print(f"setup: wrote usage_script to provider '{name}' ({args.app_type}/{pid})")
    _print_plan_summary(verified)
    print("restart cc-switch (or click the usage refresh icon) for it to load")


def cmd_refresh(args, conn, cookie: str, verified: Optional[dict]) -> None:
    pid, name, meta = find_provider(conn, args.provider, args.app_type)
    us = meta.get("usage_script")
    if not us:
        die(
            2,
            f"'{name}' has no usage_script yet; use 'setup' for first-time config",
        )
    new_code = build_script_code(cookie)
    if us.get("code") == new_code:
        print(f"refresh: cookie unchanged for '{name}' — nothing to do")
        return
    us["code"] = new_code
    us["apiKey"] = None  # we always inline; never let the misleading field linger
    write_meta(conn, pid, args.app_type, meta)
    print(f"refresh: cookie updated on '{name}' ({args.app_type}/{pid})")
    _print_plan_summary(verified)
    print("restart cc-switch (or click the usage refresh icon) for it to reload")


def cmd_delete(args, conn, cookie: Optional[str], verified: Optional[dict]) -> None:
    pid, name, meta = find_provider(conn, args.provider, args.app_type)
    if "usage_script" not in meta:
        print(f"delete: '{name}' had no usage_script — nothing to do")
        return
    del meta["usage_script"]
    write_meta(conn, pid, args.app_type, meta)
    print(f"delete: removed usage_script from '{name}' ({args.app_type}/{pid})")


def cmd_regenerate(args, conn, cookie: Optional[str], verified: Optional[dict]) -> None:
    """Re-apply the current SCRIPT_TEMPLATE using the cookie already inlined in the DB.

    Useful when the JS template changes (e.g. extractor improvements) and you want
    existing installs to pick it up without asking the user for the cookie again.
    """
    pid, name, meta = find_provider(conn, args.provider, args.app_type)
    us = meta.get("usage_script")
    if not us:
        die(2, f"'{name}' has no usage_script; nothing to regenerate")
    m = re.search(r'"Cookie":\s*"([^"]*)"', us.get("code") or "")
    if not m:
        die(
            2,
            f"could not find inlined cookie in '{name}'.usage_script.code; "
            f"run 'refresh' with a fresh cookie instead",
        )
    inlined_cookie = m.group(1)
    new_code = build_script_code(inlined_cookie)
    if us.get("code") == new_code:
        print(f"regenerate: '{name}' already on the current template — nothing to do")
        return
    us["code"] = new_code
    us["apiKey"] = None
    write_meta(conn, pid, args.app_type, meta)
    print(f"regenerate: rebuilt usage_script.code on '{name}' ({args.app_type}/{pid}) from current template")
    print("restart cc-switch (or click the usage refresh icon) for it to reload")


def cmd_status(args, conn, cookie: Optional[str], verified: Optional[dict]) -> None:
    pid, name, meta = find_provider(conn, args.provider, args.app_type)
    us = meta.get("usage_script")
    if not us:
        print(f"status: '{name}' ({args.app_type}/{pid}) — no usage_script configured")
        return
    code = us.get("code") or ""
    cookie_match = re.search(r'"Cookie":\s*"([^"]*)"', code)
    inlined_cookie = cookie_match.group(1) if cookie_match else None
    print(f"status: '{name}' ({args.app_type}/{pid})")
    print(f"  enabled        = {us.get('enabled')}")
    print(f"  templateType   = {us.get('templateType')}")
    print(f"  autoQueryEvery = {us.get('autoQueryInterval')} min")
    print(f"  code length    = {len(code)}")
    print(f"  inlined cookie = {('present, ' + str(len(inlined_cookie)) + ' chars') if inlined_cookie else 'MISSING'}")
    print(f"  legacy apiKey  = {'set (will be ignored at write time)' if us.get('apiKey') else 'cleared'}")


def cmd_verify(args, conn, cookie: str, verified: Optional[dict]) -> None:
    # verification already happened in main(); just summarize
    print("verify: cookie accepted by endpoint")
    _print_plan_summary(verified)


def _print_plan_summary(verified: Optional[dict]) -> None:
    if not verified:
        return
    print(
        f"  team           = {verified.get('teamName')!r}\n"
        f"  calls today    = {verified.get('todayUsedCount')} / {verified.get('dailyCallLimit')}\n"
        f"  amount today   = {verified.get('todayAmountCost')} / {verified.get('dailyAmountLimit')}"
    )


# ---------- main ----------

COMMANDS = {
    "setup": (cmd_setup, True),
    "refresh": (cmd_refresh, True),
    "verify": (cmd_verify, True),
    "delete": (cmd_delete, False),
    "status": (cmd_status, False),
    "regenerate": (cmd_regenerate, False),
}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=COMMANDS.keys())
    p.add_argument("--db", default=str(DEFAULT_DB), help=f"path to cc-switch.db (default: {DEFAULT_DB})")
    p.add_argument("--provider", default=DEFAULT_PROVIDER_NAME,
                   help=f"provider id or name substring (default: '{DEFAULT_PROVIDER_NAME}')")
    p.add_argument("--app-type", default="claude",
                   help="cc-switch app_type to search (default: 'claude')")
    p.add_argument("--force", action="store_true", help="setup: overwrite an existing usage_script")
    p.add_argument("--no-verify", action="store_true",
                   help="setup/refresh: skip pre-flight POST to the endpoint")
    args = p.parse_args()

    handler, needs_cookie = COMMANDS[args.command]

    cookie: Optional[str] = None
    verified: Optional[dict] = None
    if needs_cookie:
        if sys.stdin.isatty():
            die(2, f"'{args.command}' needs a cookie on stdin; "
                   f"e.g. `pbpaste | configure.py {args.command}`")
        cookie = extract_cookie_from_input(sys.stdin.read())
        if not args.no_verify:
            verified = verify_cookie(cookie)

    if not Path(args.db).exists():
        die(5, f"db not found: {args.db}")
    conn = sqlite3.connect(args.db)
    try:
        handler(args, conn, cookie, verified)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
