#!/usr/bin/env python3
"""Detect generated-binding drift and unexpected exports on the UpSPA mobile FFI boundary.

Usage (from the repository root):

    python3 scripts/check_mobile_bindings.py              # full check, regenerates bindings
    python3 scripts/check_mobile_bindings.py --self-test  # prove the check fails on seeded drift

The full check performs two independent checks and exits non-zero if either fails:

1. Drift: regenerate the Kotlin bindings into a temporary directory with the crate's own
   `uniffi-bindgen` binary (so the generator is always the crate's pinned UniFFI 0.28, resolved
   through Cargo.lock with --locked) and compare them byte-for-byte with the committed
   `apps/android/ffi/src/main/generated` tree.
2. API allowlist: extract every exported callable from the committed bindings (methods of each
   exported interface plus public top-level functions) and compare the set with
   `scripts/mobile_api_allowlist.txt`. An allowlist rather than a denylist, so a newly leaked
   helper fails even if nobody thought to name it in advance.

--self-test works on temporary copies only and never touches the committed bindings:
  a. appends a fake `nextOperationId` method to MobileEngineInterface -> allowlist check must fail;
  b. changes one line of the generated file -> drift check must fail;
  c. the unmodified committed bindings must pass the allowlist check.
"""
from __future__ import annotations

import argparse
import filecmp
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
GENERATED_DIR = REPO_ROOT / "apps/android/ffi/src/main/generated"
BINDINGS_FILE = Path("uniffi/upspa_mobile_ffi/upspa_mobile_ffi.kt")
ALLOWLIST = REPO_ROOT / "scripts/mobile_api_allowlist.txt"

# UniFFI runtime scaffolding interfaces; these are generator internals, not our contract.
RUNTIME_INTERFACE = re.compile(r"^(Ffi|Uniffi|Disposable)")
INTERFACE_HEADER = re.compile(r"^public interface (\w+)")
BACKTICK_FUN = re.compile(r"\bfun `(\w+)`\s*\(")


def strip_strings_and_comments(line: str, in_block_comment: bool) -> tuple[str, bool]:
    """Remove string literals and comments so braces inside them don't affect depth."""
    out = []
    i = 0
    while i < len(line):
        if in_block_comment:
            end = line.find("*/", i)
            if end < 0:
                return "".join(out), True
            i = end + 2
            in_block_comment = False
            continue
        if line.startswith("/*", i):
            in_block_comment = True
            i += 2
            continue
        if line.startswith("//", i):
            break
        ch = line[i]
        if ch in "\"'":
            quote = ch
            i += 1
            while i < len(line) and line[i] != quote:
                i += 2 if line[i] == "\\" else 1
            i += 1
            out.append(quote + quote)
            continue
        out.append(ch)
        i += 1
    return "".join(out), in_block_comment


def exported_api(kotlin_file: Path) -> set[str]:
    """Return {"Interface.method", "<top>.function"} for every exported callable."""
    api: set[str] = set()
    depth = 0
    current_interface: str | None = None
    interface_depth = 0
    in_block_comment = False
    for raw in kotlin_file.read_text(encoding="utf-8").splitlines():
        header = INTERFACE_HEADER.match(raw)
        if header and depth == 0 and not RUNTIME_INTERFACE.match(header.group(1)):
            current_interface = header.group(1)
            interface_depth = depth
        code, in_block_comment = strip_strings_and_comments(raw, in_block_comment)
        for match in BACKTICK_FUN.finditer(code):
            prefix = code[: match.start()]
            if current_interface is not None and depth == interface_depth + 1:
                api.add(f"{current_interface}.{match.group(1)}")
            elif depth == 0 and not re.search(r"\b(private|internal)\b", prefix):
                api.add(f"<top>.{match.group(1)}")
        depth += code.count("{") - code.count("}")
        if current_interface is not None and depth <= interface_depth:
            current_interface = None
    return api


def read_allowlist() -> set[str]:
    lines = ALLOWLIST.read_text(encoding="utf-8").splitlines()
    return {l.strip() for l in lines if l.strip() and not l.lstrip().startswith("#")}


def check_allowlist(kotlin_file: Path) -> list[str]:
    actual = exported_api(kotlin_file)
    expected = read_allowlist()
    problems = [f"unexpected export: {name}" for name in sorted(actual - expected)]
    problems += [f"missing export: {name}" for name in sorted(expected - actual)]
    return problems


def check_drift(committed: Path, fresh: Path) -> list[str]:
    problems: list[str] = []

    def walk(cmp: filecmp.dircmp, rel: Path) -> None:
        for name in cmp.left_only:
            problems.append(f"drift: {rel / name} is committed but no longer generated")
        for name in cmp.right_only:
            problems.append(f"drift: {rel / name} is generated but not committed")
        for name in cmp.diff_files:
            problems.append(f"drift: {rel / name} differs from freshly generated bindings")
        for name, sub in cmp.subdirs.items():
            walk(sub, rel / name)

    walk(filecmp.dircmp(committed, fresh, ignore=[]), Path("."))
    # Drop dircmp's content verdicts and compare every common file ourselves, normalizing line
    # endings: a Windows checkout with core.autocrlf may hold CRLF copies of LF-generated files.
    problems = [p for p in problems if "differs from freshly generated" not in p]
    for path in sorted(committed.rglob("*")):
        if path.is_file():
            other = fresh / path.relative_to(committed)
            if other.is_file() and normalized(path) != normalized(other):
                problems.append(
                    f"drift: {path.relative_to(committed).as_posix()} differs from freshly generated bindings"
                )
    return problems


def normalized(path: Path) -> bytes:
    return path.read_bytes().replace(b"\r\n", b"\n")


def regenerate(out_dir: Path) -> None:
    """Regenerate Kotlin bindings into out_dir exactly as scripts/generate_mobile_bindings.sh does.

    Calls cargo directly instead of the shell script, so it behaves the same on Linux, macOS and
    Windows (where a bare `bash` can resolve to WSL instead of Git Bash).
    """
    target_dir = Path(os.environ.get("CARGO_TARGET_DIR", REPO_ROOT / "target"))
    if sys.platform == "darwin":
        lib_name = "libupspa_mobile_ffi.dylib"
    elif os.name == "nt":
        lib_name = "upspa_mobile_ffi.dll"
    else:
        lib_name = "libupspa_mobile_ffi.so"
    lib_path = target_dir / "release" / lib_name
    out_dir.mkdir(parents=True, exist_ok=True)
    commands = [
        ["cargo", "build", "--locked", "--profile", "release", "-p", "upspa-mobile-ffi"],
        ["cargo", "run", "--locked", "--profile", "release", "-p", "upspa-mobile-ffi",
         "--bin", "uniffi-bindgen", "--", "generate", "--library", str(lib_path),
         "--language", "kotlin", "--out-dir", str(out_dir), "--no-format"],
    ]
    for command in commands:
        result = subprocess.run(command, cwd=REPO_ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        if result.returncode != 0:
            sys.stdout.write(result.stdout)
            raise SystemExit(f"binding generation failed: {' '.join(command[:3])}")
    if not lib_path.is_file():
        raise SystemExit(f"expected library not found at {lib_path}")


def report(title: str, problems: list[str]) -> bool:
    if problems:
        print(f"[FAIL] {title}")
        for p in problems:
            print(f"       {p}")
        return False
    print(f"[ OK ] {title}")
    return True


def run_check() -> int:
    ok = True
    with tempfile.TemporaryDirectory(prefix="upspa-bindings-") as tmp:
        fresh = Path(tmp) / "generated"
        regenerate(fresh)
        ok &= report("committed bindings match regenerated bindings", check_drift(GENERATED_DIR, fresh))
    ok &= report("exported API matches scripts/mobile_api_allowlist.txt", check_allowlist(GENERATED_DIR / BINDINGS_FILE))
    if not ok:
        print("\nIf the change is intentional: run scripts/generate_mobile_bindings.sh, update the")
        print("allowlist, and explain the contract change in the PR description.")
    return 0 if ok else 1


def run_self_test() -> int:
    ok = True
    with tempfile.TemporaryDirectory(prefix="upspa-bindings-selftest-") as tmp:
        tmp = Path(tmp)

        # c. baseline: the committed bindings pass the allowlist.
        ok &= report("self-test: committed bindings pass the allowlist",
                     check_allowlist(GENERATED_DIR / BINDINGS_FILE))

        # a. seeded leak: an internal helper appears in the engine interface.
        leaked = tmp / "leaked"
        shutil.copytree(GENERATED_DIR, leaked)
        kt = leaked / BINDINGS_FILE
        text = kt.read_text(encoding="utf-8")
        marker = "public interface MobileEngineInterface {\n"
        assert marker in text, "MobileEngineInterface not found in generated bindings"
        kt.write_text(text.replace(marker, marker + "    fun `nextOperationId`(): OperationId\n", 1),
                      encoding="utf-8")
        problems = check_allowlist(kt)
        seeded_ok = problems == ["unexpected export: MobileEngineInterface.nextOperationId"]
        ok &= report("self-test: seeded leaked helper is rejected by the allowlist",
                     [] if seeded_ok else [f"checker did not fail as expected: {problems}"])

        # b. seeded drift: committed file edited by hand.
        drifted = tmp / "drifted"
        shutil.copytree(GENERATED_DIR, drifted)
        kt = drifted / BINDINGS_FILE
        kt.write_text(kt.read_text(encoding="utf-8") + "\n// hand edit\n", encoding="utf-8")
        problems = check_drift(drifted, GENERATED_DIR)
        drift_ok = len(problems) == 1 and "differs from freshly generated" in problems[0]
        ok &= report("self-test: seeded hand edit is detected as drift",
                     [] if drift_ok else [f"checker did not fail as expected: {problems}"])
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="prove the check fails on seeded drift")
    args = parser.parse_args()
    return run_self_test() if args.self_test else run_check()


if __name__ == "__main__":
    sys.exit(main())
