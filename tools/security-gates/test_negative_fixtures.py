#!/usr/bin/env python3
"""
Runs every seeded-negative fixture under fixtures/negative/<gate_name>/
and proves two things for each one:

  1. The gate it's named after actually reports a FAIL against it
     (the check has real detection power, not just a rubber stamp).
  2. No other gate reports a FAIL against the same fixture
     (the fixture is genuinely isolated to one thing).

This file complies with "completion requires the normal tree to pass every gate
and every seeded-negative case to fail for the intended reason";
each fixture should fail its intended gate and pass all other gates.

Usage: python3 test_negative_fixtures.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from gates import (
    accessibility_clipboard,
    backup_config,
    exported_components,
    network_cleartext,
    pending_intent,
    screenshot_recents,
    secret_logging,
    uniffi_secret_fields,
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

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "negative"

def main() -> int:
    if not FIXTURES_DIR.exists():
        print(f"ERROR: no fixtures directory at {FIXTURES_DIR}", file=sys.stderr)
        return 2

    fixture_dirs = sorted(p for p in FIXTURES_DIR.iterdir() if p.is_dir())
    if not fixture_dirs:
        print(f"ERROR: no fixture subdirectories under {FIXTURES_DIR}", file=sys.stderr)
        return 2

    overall_ok = True
    print(f"Running {len(fixture_dirs)} seeded-negative fixture(s)...\n")

    for fixture_dir in fixture_dirs:
        gate_name = fixture_dir.name
        if gate_name not in GATES:
            print(f"[SKIP] {gate_name}: no gate named this. "
                  f"Fixture directory name must match a key in GATES.")
            overall_ok = False
            continue

        # Run every gate (not just the target one) against this single fixture
        results = {name: gate_func(fixture_dir) for name, gate_func in GATES.items()}
        target_fails = [f for f in results[gate_name] if f.severity == Severity.FAIL]
        other_fails = {
            name: [f for f in findings if f.severity == Severity.FAIL]
            for name, findings in results.items()
            if name != gate_name
        }
        other_fails = {name: f for name, f in other_fails.items() if f}

        if target_fails and not other_fails:
            print(f"[PASS] {gate_name}: fails for the intended reason, "
                  f"and only that gate fires ({len(target_fails)} finding(s)).")
        elif not target_fails:
            print(f"[FAIL] {gate_name}: the gate this fixture is named after reported NO finding; "
                  f"the check has no detection power here, or the fixture stopped matching the gate's rule.")
            overall_ok = False
        else:
            print(f"[FAIL] {gate_name}: correctly fails its own gate, "
                  f"BUT also triggers {list(other_fails)}; fixture is not isolated.")
            for name, findings in other_fails.items():
                for f in findings:
                    print(f"        also-fired [{name}] {f.detail[:100]}")
            overall_ok = False

    print()
    print("All fixtures isolated and correct." if overall_ok
          else "One or more fixtures failed the isolation/detection check above.")
    return 0 if overall_ok else 1

if __name__ == "__main__":
    raise SystemExit(main())
