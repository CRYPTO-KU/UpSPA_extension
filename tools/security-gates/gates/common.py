"""
Shared common for the mobile security gates.

Design notes (read this before adding a new gate):

- Every gate is a plain function: `run(repo_root: Path) -> list[Finding]`.
  No shared mutable state, no plugin registry magic; `run_gates.py` just
  imports each module and calls `run()`. Keeps this "dependency-light" and
  easy to run as one negative-fixture at a time in isolation.
- Prefer structured parsing when a stdlib parser exists for the file type
  (AndroidManifest.xml -> xml.etree.ElementTree). Kotlin and Rust have no
  practical dependency-light parser available here, so those gates use
  targeted, documented regexes instead of a full grammar. Where a regex
  gate could plausibly be fooled by formatting tricks (multi-line calls,
  string concatenation), that limitation is stated in the gate's own
  docstring rather than silently assumed away.
- A Finding is either a real hit (`severity=FAIL`) or informational
  (`severity=INFO`). Only FAIL findings affect the process exit code.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path


class Severity(str, Enum):
    FAIL = "FAIL"
    INFO = "INFO"
    PASS = "PASS"  # used only for the zero-findings summary line

@dataclass
class Finding:
    gate: str
    severity: Severity
    file: str
    detail: str
    line: int | None = None

    def format(self) -> str:
        loc = f"{self.file}:{self.line}" if self.line else self.file
        return f"[{self.severity.value}] {self.gate}: {loc}: {self.detail}"

@dataclass
class GateResult:
    gate: str
    findings: list[Finding] = field(default_factory=list)

    @property
    def failed(self) -> bool:
        return any(f.severity == Severity.FAIL for f in self.findings)

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Directories that are never gate targets: build output, node_modules-style
# dependency trees, and this tool's own seeded-negative fixtures (scanned
# individually, one family at a time, by the isolated fixture runner;
# never as part of a whole-repo scan, or a deliberately-broken snippet
# would get mixed into real findings).
SKIP_DIR_NAMES = {
    ".git", "build", ".gradle", "node_modules", "target", "dist",
}
OWN_FIXTURES_PATH = ("tools", "security-gates", "fixtures")

# apps/android/fixtures is the controlled test-target app:
# a synthetic login/registration/etc form that plays the role of
# an ordinary third-party app being autofilled INTO.
# It is deliberately not security-hardened; demanding FLAG_SECURE or a
# locked-down manifest on a fixture pretending to be another app's login
# screen would be testing the wrong app. Product-security gates apply to
# UpSPA itself (apps/android/app) and exclude this module explicitly,
# rather than silently happening to skip it.
FIXTURE_APP_MODULE = ("apps", "android", "fixtures")

def _has_path_prefix(path: Path, prefix: tuple[str, ...]) -> bool:
    parts = path.parts
    return any(parts[i:i + len(prefix)] == prefix for i in range(len(parts)))

def iter_files(root: Path, *suffixes: str, exclude_fixture_app: bool = True):
    for path in root.rglob("*"):
        if path.is_dir():
            continue
        rel = path.relative_to(root) if path.is_relative_to(root) else path
        if any(part in SKIP_DIR_NAMES for part in path.parts):
            continue
        if _has_path_prefix(rel, OWN_FIXTURES_PATH):
            continue
        if exclude_fixture_app and _has_path_prefix(rel, FIXTURE_APP_MODULE):
            continue
        if suffixes and path.suffix not in suffixes:
            continue
        yield path

def find_manifests(root: Path, exclude_fixture_app: bool = True) -> list[Path]:
    """AndroidManifest.xml files under app source sets, excluding this
    tool's own negative fixtures and fixture-app module."""
    out = []
    for p in root.rglob("AndroidManifest.xml"):
        rel = p.relative_to(root) if p.is_relative_to(root) else p
        if any(part in SKIP_DIR_NAMES for part in p.parts):
            continue
        if _has_path_prefix(rel, OWN_FIXTURES_PATH):
            continue
        if exclude_fixture_app and _has_path_prefix(rel, FIXTURE_APP_MODULE):
            continue
        out.append(p)
    return out

def parse_manifest(path: Path) -> ET.Element:
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    tree = ET.parse(path, parser=parser)
    return tree.getroot()

def android_attr(element: ET.Element, name: str) -> str | None:
    return element.get(f"{ANDROID_NS}{name}")

def line_of(path: Path, needle_regex: str) -> int | None:
    """Best-effort line lookup for a regex match, for readable findings.
    Returns the first matching line number, or None."""
    try:
        text = path.read_text(errors="ignore")
    except OSError:
        return None
    pattern = re.compile(needle_regex)
    for i, line in enumerate(text.splitlines(), start=1):
        if pattern.search(line):
            return i
    return None
