#!/usr/bin/env python3
"""
Runs every seeded-positive fixture under fixtures/positive/<gate_name>/
Proves a seeded good case stays clean across every gate,
not just the one it's named after.

Usage: python3 test_positive_fixtures.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from gates import (
    accessibility_clipboard, backup_config, exported_components,
    network_cleartext, pending_intent, screenshot_recents,
    secret_logging, uniffi_secret_fields,
)
from gates.common import Severity

GATES = {
    "exported_components": exported_components.run,
    "backup_config": backup_config.run,
    "accessibility_clipboard": accessibility_clipboard.run,
    "screenshot_recents": screenshot_recents.run,
    "pending_intent": pending_intent.run,
    "network_cleartext": network_cleartext.run,
    "secret_logging": secret_logging.run,
    "uniffi_secret_fields": uniffi_secret_fields.run,
}

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "positive"


def main() -> int:
    if not FIXTURES_DIR.exists():
        print(f"No positive fixtures directory at {FIXTURES_DIR}; nothing to run.")
        return 0

    fixture_dirs = sorted(p for p in FIXTURES_DIR.iterdir() if p.is_dir())
    overall_ok = True
    print(f"Running {len(fixture_dirs)} seeded-positive fixture(s)\n")

    for fixture_dir in fixture_dirs:
        gate_name = fixture_dir.name
        if gate_name not in GATES:
            print(f"[SKIP] {gate_name}: no matching gate.")
            overall_ok = False
            continue

        results = {name: fn(fixture_dir) for name, fn in GATES.items()}
        all_fails = {n: [f for f in fs if f.severity == Severity.FAIL]
                     for n, fs in results.items()}
        all_fails = {n: f for n, f in all_fails.items() if f}

        if not all_fails:
            print(f"[PASS] {gate_name}: correctly produces no findings from any gate.")
        else:
            print(f"[FAIL] {gate_name}: expected zero findings, got FAILs from {list(all_fails)}")
            overall_ok = False

    print()
    print("All positive fixtures stayed clean." if overall_ok
          else "One or more positive fixtures unexpectedly failed a gate.")
    return 0 if overall_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
