#!/usr/bin/env python3
"""Run recovery regressions and two isolated faults; never mutate the checkout.

Uses only the standard library, Cargo, and the committed workspace/lockfile.
Copies tracked source files from the working tree, including local test edits.
A compile/setup failure or zero executed tests is not fault detection.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("crates/upspa-core/src/recovery_engine.rs")
FAULTS = (
    (
        "exact-deadline",
        "if self.deadline_ms.is_some_and(|deadline| now_ms >= deadline) {",
        "if self.deadline_ms.is_some_and(|deadline| now_ms > deadline) {",
        "replay_exact_deadline_rejects_completion_control",
    ),
    (
        "restore-reply-correlation",
        "if &reply.request_id != expected {",
        "if false && &reply.request_id != expected {",
        "replay_restore_rejects_rebound_request_control",
    ),
)


def run_test(workspace, test=None):
    command = ["cargo", "test", "--locked", "-p", "upspa-core",
               "--test", "recovery_engine_replay"]
    if test:
        command.extend([test, "--", "--exact"])
    print("+ " + " ".join(command), flush=True)
    env = os.environ.copy()
    # Build artifacts remain disposable too; no mutated library can be reused
    # accidentally by a later command in the real checkout.
    env["CARGO_TARGET_DIR"] = str(workspace / "target")
    return subprocess.run(command, cwd=workspace, env=env, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def require_pass(result, count):
    expected = f"test result: ok. {count} passed; 0 failed; 0 ignored;"
    if result.returncode != 0 or expected not in result.stdout:
        raise RuntimeError("Control did not execute and pass:\n" + result.stdout)


def require_detected(result, test):
    if (result.returncode != 101
            or not re.search(rf"^test {re.escape(test)} \.\.\. FAILED$", result.stdout, re.M)
            or "test result: FAILED. 0 passed; 1 failed; 0 ignored;" not in result.stdout
            or f"---- {test} stdout ----" not in result.stdout):
        raise RuntimeError("Fault was not detected by the named executed test:\n" + result.stdout)


def main():
    paths = subprocess.check_output([
        "git", "ls-files", "-z", "--", "Cargo.toml", "Cargo.lock",
        "rust-toolchain.toml", ".cargo", "crates", "test-vectors",
    ], cwd=ROOT).decode().split("\0")
    with tempfile.TemporaryDirectory(prefix="upspa-recovery-controls-") as temporary:
        workspace = Path(temporary)
        for name in filter(None, paths):
            source = ROOT / name
            target = workspace / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
        engine = workspace / SOURCE
        original = engine.read_text()
        require_pass(run_test(workspace), 9)
        print("PASS: unmodified replay suite, 9 executed tests", flush=True)
        for label, before, after, test in FAULTS:
            if original.count(before) != 1:
                raise RuntimeError(f"{label}: expected exactly one mutation site")
            try:
                engine.write_text(original.replace(before, after))
                require_detected(run_test(workspace, test), test)
                print(f"DETECTED: {label} by {test} (1 failed, exit 101)", flush=True)
            finally:
                engine.write_text(original)
            require_pass(run_test(workspace, test), 1)
            print(f"PASS: restored control {test}", flush=True)
        require_pass(run_test(workspace), 9)
        print("PASS: final unmodified replay suite, 9 executed tests", flush=True)
    print("PASS: both faults detected; temporary workspace removed", flush=True)


if __name__ == "__main__":
    main()
