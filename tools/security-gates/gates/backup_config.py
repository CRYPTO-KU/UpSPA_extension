"""
Gate: backup configuration.

Maps to MASWE-0006 (Sensitive Data Not Excluded From Backup).

Structurally parsed <application> attributes in AndroidManifest.xml:

  - android:allowBackup missing or "true" -> FAIL.
    The platform default is true, so an absent attribute is exactly
    as dangerous as an explicit true and must not be treated as a pass.
  - android:allowBackup="false" -> base case satisfied.
  - If allowBackup is false: fullBackupContent or dataExtractionRules are
    optional extra hardening (INFO if absent, not a FAIL) since backup is
    already fully disabled; they only matter when allowBackup is true,
    which this gate already fails on independently.
"""
from __future__ import annotations

from pathlib import Path

from .common import (
    Finding,
    Severity,
    android_attr,
    find_manifests,
    parse_manifest,
)


def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []

    for manifest_path in find_manifests(repo_root):
        try:
            root = parse_manifest(manifest_path)
        except Exception as exc:
            findings.append(Finding(
                gate="backup_config", severity=Severity.FAIL,
                file=str(manifest_path), detail=f"manifest failed to parse: {exc}",
            ))
            continue

        rel = str(manifest_path.relative_to(repo_root))
        application = root.find("application")
        if application is None:
            continue

        allow_backup = android_attr(application, "allowBackup")

        if allow_backup is None:
            findings.append(Finding(
                gate="backup_config", severity=Severity.FAIL, file=rel,
                detail=(
                    "android:allowBackup is not set on <application>. "
                    "The platform default is true, so this behaves the same "
                    "as an explicit true. Set android:allowBackup=\"false\"."
                ),
            ))
            continue

        if allow_backup != "false":
            findings.append(Finding(
                gate="backup_config", severity=Severity.FAIL, file=rel,
                detail=(
                    f'android:allowBackup="{allow_backup}"; app data '
                    f"(including any locally cached protocol state) "
                    f"can be extracted via adb backup or cloud auto-backup."
                ),
            ))
            continue

        # allowBackup=false confirmed; extra rules are informational only.
        full_backup = android_attr(application, "fullBackupContent")
        extraction_rules = android_attr(application, "dataExtractionRules")
        if full_backup is None and extraction_rules is None:
            findings.append(Finding(
                gate="backup_config", severity=Severity.INFO, file=rel,
                detail=(
                    "allowBackup=false (good) with no fullBackupContent or "
                    "dataExtractionRules set; no action needed while backup "
                    "stays fully disabled, but if allowBackup is ever "
                    "flipped to true later, there would be no exclusion "
                    "rule in place to catch it."
                ),
            ))

    return findings
