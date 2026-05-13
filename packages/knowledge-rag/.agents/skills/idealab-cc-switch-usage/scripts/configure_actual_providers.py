#!/usr/bin/env python3
"""Configure the actual local cc-switch Idea LAB providers.

This helper is intentionally tiny and reads the curl/cookie from stdin so
credentials do not need to be passed as command-line arguments.
"""

from __future__ import annotations

import datetime
import importlib.util
import json
import shutil
import sqlite3
import sys
from pathlib import Path


SKILL_SCRIPT = Path(__file__).with_name("configure.py")
TARGETS = (
    ("openclaw", "ideaLab"),
    ("opencode", "idea-lab"),
    ("opencode", "idea-lab-anthropic"),
)


def load_configure_module():
    spec = importlib.util.spec_from_file_location("ccswitch_usage_configure", SKILL_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load {SKILL_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main() -> None:
    mod = load_configure_module()
    cookie = mod.extract_cookie_from_input(sys.stdin.read())
    verified = mod.verify_cookie(cookie)

    db = Path.home() / ".cc-switch" / "cc-switch.db"
    if not db.exists():
        mod.die(5, f"db not found: {db}")

    backup = Path("/private/tmp") / f"cc-switch.db.bak-{datetime.datetime.now().strftime('%Y%m%d-%H%M%S')}"
    shutil.copy2(db, backup)

    usage_script = {
        "enabled": True,
        "language": "javascript",
        "code": mod.build_script_code(cookie),
        "timeout": 10,
        "templateType": "custom",
        "apiKey": None,
        "autoQueryInterval": 5,
    }

    conn = sqlite3.connect(db)
    try:
        for app_type, provider_id in TARGETS:
            row = conn.execute(
                "SELECT name, meta FROM providers WHERE app_type=? AND id=?",
                (app_type, provider_id),
            ).fetchone()
            if not row:
                print(f"skip: missing {app_type}/{provider_id}")
                continue

            name, meta_raw = row
            meta = json.loads(meta_raw or "{}")
            action = "refreshed" if meta.get("usage_script") else "configured"
            meta["usage_script"] = usage_script
            conn.execute(
                "UPDATE providers SET meta=? WHERE app_type=? AND id=?",
                (json.dumps(meta, ensure_ascii=False), app_type, provider_id),
            )
            print(f"{action}: {app_type}/{provider_id} ({name})")
        conn.commit()
    finally:
        conn.close()

    print(f"backup: {backup}")
    mod._print_plan_summary(verified)
    print("restart cc-switch (or click the usage refresh icon) for it to reload")


if __name__ == "__main__":
    main()
