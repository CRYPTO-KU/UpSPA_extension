"""
Gate: Accessibility and clipboard-based credential delivery.

Maps to MASWE-0040 (Sensitive Data Leaked via Accessibility Services) and
MASWE-0030 (clipboard). UpSPA's own autofill path is the Android Autofill
Framework or Credential Provider API; Accessibility has no legitimate role
in this app at all, and Google Play now policy-prohibits this class of
automation regardless. This gate treats any trace of it as a FAIL,
with no exceptions.

Two independent surfaces, both regex-based (no practical dependency-light
structured parser for Kotlin exists here; see gates/common.py docstring):

1. Manifest: any <service> whose android:permission is
   BIND_ACCESSIBILITY_SERVICE, or whose intent-filter action is
   android.accessibilityservice.AccessibilityService.
2. Kotlin/Java source: references to AccessibilityService,
   AccessibilityNodeInfo, or the ClipboardManager credential-delivery
   pattern (setting a credential-shaped value onto a ClipData, which is
   plain clipboard usage unrelated to credentials, e.g. "copy diagnostic ID",
   is not itself a finding; the trigger is a clipboard write whose source
   variable name looks credential-shaped).

Known limitation: the clipboard check is a name-based heuristic (variable
names containing password/secret/credential/token near a ClipData/
setPrimaryClip call within a few lines). It will miss credential data that
reaches the clipboard through a variable with a generic name, and it can
false-positive on unrelated code that merely mentions "token" near a
clipboard call. Treat findings as a prompt for human review, not as
automatically certain.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import (
    Finding,
    Severity,
    android_attr,
    find_manifests,
    iter_files,
    parse_manifest,
)

ACCESSIBILITY_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"
ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"

ACCESSIBILITY_SOURCE_PATTERN = re.compile(
    r"\bAccessibilityService\b|\bAccessibilityNodeInfo\b|\bAccessibilityEvent\b"
)
CLIPBOARD_CALL_PATTERN = re.compile(r"setPrimaryClip|ClipData\.newPlainText")
CREDENTIAL_NAME_HINT = re.compile(
    r"password|secret|credential|token|master.?key|Rlsj|Rsp|ssk", re.IGNORECASE
)

def _check_manifests(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for manifest_path in find_manifests(repo_root):
        try:
            root = parse_manifest(manifest_path)
        except Exception:
            continue  # malformed-XML already reported by other manifest gates
        rel = str(manifest_path.relative_to(repo_root))
        for service in root.iter("service"):
            name = android_attr(service, "name") or "(unnamed)"
            permission = android_attr(service, "permission")
            if permission == ACCESSIBILITY_PERMISSION:
                findings.append(Finding(
                    gate="accessibility_clipboard", severity=Severity.FAIL,
                    file=rel,
                    detail=(
                        f'<service android:name="{name}"> declares BIND_ACCESSIBILITY_SERVICE. '
                        f"UpSPA has no legitimate use for the Accessibility API; "
                        f"this is also now Google Play policy-prohibited for this class of app."
                    ),
                ))
                continue
            for intent_filter in service.findall("intent-filter"):
                for action in intent_filter.findall("action"):
                    if android_attr(action, "name") == ACCESSIBILITY_ACTION:
                        findings.append(Finding(
                            gate="accessibility_clipboard", severity=Severity.FAIL,
                            file=rel,
                            detail=(
                                f'<service android:name="{name}"> declares '
                                f"an intent-filter for the AccessibilityService action."
                            ),
                        ))
    return findings

def _check_sources(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_files(repo_root, ".kt", ".java"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        rel = str(path.relative_to(repo_root))
        lines = text.splitlines()

        for i, line in enumerate(lines, start=1):
            if ACCESSIBILITY_SOURCE_PATTERN.search(line):
                findings.append(Finding(
                    gate="accessibility_clipboard", severity=Severity.FAIL,
                    file=rel, line=i,
                    detail=f"Accessibility API referenced: {line.strip()[:140]}",
                ))

        for i, line in enumerate(lines, start=1):
            if CLIPBOARD_CALL_PATTERN.search(line):
                window = "\n".join(lines[max(0, i - 3):i + 1])
                if CREDENTIAL_NAME_HINT.search(window):
                    findings.append(Finding(
                        gate="accessibility_clipboard", severity=Severity.FAIL,
                        file=rel, line=i,
                        detail=(
                            f"clipboard write near a credential-shaped identifier: {line.strip()[:140]}"
                        ),
                    ))
    return findings

def run(repo_root: Path) -> list[Finding]:
    return _check_manifests(repo_root) + _check_sources(repo_root)
