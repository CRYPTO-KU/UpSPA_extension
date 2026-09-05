"""
Gate: screenshot and recents protection.

Maps to:
  - MASVS-STORAGE-1/2
  - MASWE-0038 (Insufficient Protection of Sensitive Data from Screenshots)

Scope: UpSPA's own app (apps/android/app) only. apps/android/fixtures is
the controlled test-target app; a synthetic login or registration form
playing the role of an ordinary third-party app being autofilled INTO.
It is deliberately not hardened, on purpose, and is excluded here
the same way gates/common.py excludes it by default everywhere.

This gate does NOT require every Activity to set FLAG_SECURE; most of the
app's screens (e.g. MainActivity's bootstrap/settings screen) show nothing
sensitive and a blanket rule would just teach reviewers to ignore findings.
It also only ever looks at files that are actually an Activity subclass
(`class X : ComponentActivity` / `AppCompatActivity` / etc.). A service,
engine, or classifier file that merely mentions a secret-shaped identifier
has no window to protect in the first place, and flagging it would be a
category error, not just a false positive.

Within these Activities, a screen is considered in scope if
it is credential-related in either of two ways:

1. Name-based: the class name matches a credential/auth/unlock pattern
   (CredentialAuthActivity, UnlockActivity, etc.).
2. Content-based: the file references protocol-specific secret identifiers
   (Rlsj, Rsp, ssk, K0, masterKey); deliberately NOT the bare English word
   "password", which shows up constantly in ordinary descriptive UI copy
   for a password manager (e.g. MainActivity's "does not collect a master
   password" disclaimer) and would flag nearly every screen in the app.

For each in-scope Activity, the file must contain FLAG_SECURE (window flag)
or setRecentsScreenshotEnabled(false). Missing either is a FAIL. An in-scope
Activity that also lacks android:excludeFromRecents in the manifest gets a
separate INFO (defense-in-depth, not required if FLAG_SECURE is present,
since FLAG_SECURE alone already blocks the recents thumbnail content).

Known limitation: this is per-file regex matching, so a credential Activity
split across multiple files (e.g. flag set in a shared base class) will be
missed unless the base class file itself matches the name or content triggers.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Severity, android_attr, find_manifests, iter_files, parse_manifest

ACTIVITY_SUBCLASS_PATTERN = re.compile(
    r"class\s+\w+\s*:\s*\w*Activity\b|extends\s+\w*Activity\b"
)
CREDENTIAL_NAME_PATTERN = re.compile(
    r"(Credential|Unlock|Auth(?!ofill)|MasterPassword|Vault)", re.IGNORECASE
)
CREDENTIAL_CONTENT_PATTERN = re.compile(
    r"\bRlsj\b|\bRsp\b|\bssk\b|\bK0\b|masterKey|master_key",
)
FLAG_SECURE_PATTERN = re.compile(r"FLAG_SECURE|setRecentsScreenshotEnabled\s*\(\s*false\s*\)")
ACTIVITY_CLASS_PATTERN = re.compile(r"class\s+(\w+)\s*:\s*\w*Activity")

def _looks_like_credential_activity(path: Path, text: str) -> bool:
    if not ACTIVITY_SUBCLASS_PATTERN.search(text):
        return False  # not an Activity at all; no window, nothing to protect
    if CREDENTIAL_NAME_PATTERN.search(path.stem):
        return True
    return bool(CREDENTIAL_CONTENT_PATTERN.search(text))

def _manifest_activity_attrs(repo_root: Path) -> dict[str, dict[str, str | None]]:
    """Map simple class name -> manifest attributes, best-effort (matches on
    the trailing component of android:name)."""
    out: dict[str, dict[str, str | None]] = {}
    for manifest_path in find_manifests(repo_root):
        try:
            root = parse_manifest(manifest_path)
        except Exception:
            continue
        for activity in root.iter("activity"):
            name = android_attr(activity, "name") or ""
            simple = name.rsplit(".", 1)[-1].lstrip(".")
            out[simple] = {
                "excludeFromRecents": android_attr(activity, "excludeFromRecents"),
                "exported": android_attr(activity, "exported"),
            }
    return out

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    manifest_activities = _manifest_activity_attrs(repo_root)

    for path in iter_files(repo_root, ".kt", ".java"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        if not _looks_like_credential_activity(path, text):
            continue

        rel = str(path.relative_to(repo_root))
        has_flag = bool(FLAG_SECURE_PATTERN.search(text))
        if not has_flag:
            findings.append(Finding(
                gate="screenshot_recents", severity=Severity.FAIL, file=rel,
                detail=(
                    "this Activity looks credential-related (by name or "
                    "by referencing secret-shaped values) but does not set "
                    "FLAG_SECURE or setRecentsScreenshotEnabled(false); "
                    "its content can be captured in a screenshot or the "
                    "recents thumbnail."
                ),
            ))
            continue  # don't also emit the INFO below if the FAIL already fires

        class_match = ACTIVITY_CLASS_PATTERN.search(text)
        class_name = class_match.group(1) if class_match else path.stem
        manifest_entry = manifest_activities.get(class_name)
        if manifest_entry and manifest_entry.get("excludeFromRecents") != "true":
            findings.append(Finding(
                gate="screenshot_recents", severity=Severity.INFO, file=rel,
                detail=(
                    f"{class_name} sets FLAG_SECURE (good) but its manifest "
                    f"entry does not set android:excludeFromRecents=\"true\"; "
                    f"not required since FLAG_SECURE already blocks the "
                    f"thumbnail's content, but worth doing for defense in "
                    f"depth."
                ),
            ))

    return findings
