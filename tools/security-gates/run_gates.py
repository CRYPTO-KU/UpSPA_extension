#!/usr/bin/env python3
"""
Single entry point for all mobile security gates.

Usage: python3 run_gates.py [repo_root] [--gate NAME] [--json]

Default repo_root is the current working directory.
Returns 0 if no FAIL-severity findings were produced, 1 otherwise.

Each gate is a standalone module under 'gates/' with
a 'run(repo_root) -> list[Finding]' function;
this file just imports and calls each one and formats the combined output.
See gates/common.py for the shared finding or severity types
and the design notes at the top of that file.
"""
from __future__ import annotations

import argparse
import json
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

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo_root", nargs="?", default=".", type=Path)
    parser.add_argument(
        "--gate", action="append", dest="gates", choices=sorted(GATES),
        help="Run only this gate (repeatable). Default: run all gates.",
    )
    parser.add_argument("--json", action="store_true", help="Machine-readable output.")
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    if not repo_root.exists():
        print(f"error: repo root does not exist: {repo_root}", file=sys.stderr)
        return 2

    selected = args.gates or sorted(GATES)
    all_findings = []
    for name in selected:
        all_findings.extend(GATES[name](repo_root))

    fails = [f for f in all_findings if f.severity == Severity.FAIL]
    infos = [f for f in all_findings if f.severity == Severity.INFO]

    if args.json:
        print(json.dumps(
            [{"gate": f.gate, "severity": f.severity.value, "file": f.file,
              "line": f.line, "detail": f.detail} for f in all_findings],
            indent=2,
        ))
    else:
        print(f"Mobile security gates in {repo_root}")
        print(f"Gates run: {', '.join(selected)}\n")
        if not all_findings:
            print("[PASS] no findings from any gate.")
        else:
            for f in all_findings:
                print(f.format())
            print()
            print(f"Summary: {len(fails)} FAIL, {len(infos)} INFO "
                  f"across {len(selected)} gate(s).")

    return 1 if fails else 0

if __name__ == "__main__":
    raise SystemExit(main())
