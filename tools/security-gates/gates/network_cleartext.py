"""
Gate: unexpected INTERNET permission or cleartext network configuration.

Maps to:
  - MASVS-NETWORK-1
  - MASWE-0026 (Network Traffic Not Encrypted)

Two checks, both structurally parsed from XML:

1. <uses-permission android:name="android.permission.INTERNET">
   The current walking-skeleton build intentionally has none. Because this
   track is an executable security gate for the current baseline, unexpected
   network capability is a hard FAIL; when real protocol networking lands,
   the rule should be revisited together with the network design rather
   than silently allowing the capability to appear.

2. android:usesCleartextTraffic on <application>, and
   cleartextTrafficPermitted in any referenced network_security_config.xml
   These ARE hard FAILs if set to allow cleartext, regardless of whether
   INTERNET is present yet, because there's no legitimate reason for this
   app to ever accept a cleartext fallback to a storage provider or login
   server.
"""
from __future__ import annotations

from pathlib import Path

from .common import Finding, Severity, android_attr, find_manifests, parse_manifest

import re
from xml.etree import ElementTree as ET

INTERNET_PERMISSION = "android.permission.INTERNET"

WAIVER_PATTERN = re.compile(r'gates:allow-internet\s+reason="([^"]*)"')

def _waiver_reason_before(manifest_root: ET.Element, target: ET.Element) -> str | None:
    """Reason string if `target` is a direct child of `manifest_root` and the
    immediately preceding sibling is a matching gates:allow-internet comment.
    Requires *immediate* adjacency; a waiver elsewhere in the file doesn't count."""
    children = list(manifest_root)
    try:
        idx = children.index(target)
    except ValueError:
        return None
    if idx == 0:
        return None
    prev = children[idx - 1]
    if prev.tag is not ET.Comment:
        return None
    match = WAIVER_PATTERN.search(prev.text or "")
    return match.group(1) if match else None

def _check_internet_permission(root, rel: str) -> list[Finding]:
    findings = []
    for perm in root.iter("uses-permission"):
        if android_attr(perm, "name") != INTERNET_PERMISSION:
            continue
        reason = _waiver_reason_before(root, perm)
        if reason:
            findings.append(Finding(
                gate="network_cleartext", severity=Severity.INFO, file=rel,
                detail=(
                    f'INTERNET permission is present, waived via '
                    f'gates:allow-internet comment: "{reason}".'
                ),
            ))
        else:
            findings.append(Finding(
                gate="network_cleartext", severity=Severity.FAIL, file=rel,
                detail=(
                    "INTERNET permission is present, but the current "
                    "mobile-dev baseline intentionally requires no network "
                    "capability. If this addition is intentional (e.g. real "
                    "protocol networking landing), add a waiver comment "
                    "immediately above the <uses-permission> element: "
                    '<!-- gates:allow-internet reason="..." -->'
                ),
            ))
    return findings

def _check_cleartext_application_flag(root, rel: str) -> list[Finding]:
    findings = []
    application = root.find("application")
    if application is None:
        return findings
    cleartext = android_attr(application, "usesCleartextTraffic")
    if cleartext == "true":
        findings.append(Finding(
            gate="network_cleartext", severity=Severity.FAIL, file=rel,
            detail=(
                'android:usesCleartextTraffic="true" on <application>; '
                "permits unencrypted traffic app-wide."
            ),
        ))
    return findings

def _check_network_security_config(repo_root: Path) -> list[Finding]:
    findings = []
    for nsc_path in repo_root.rglob("network_security_config.xml"):
        if any(part in ("build", ".git") for part in nsc_path.parts):
            continue
        rel = str(nsc_path.relative_to(repo_root))
        try:
            root = parse_manifest(nsc_path)
        except Exception as exc:
            findings.append(Finding(
                gate="network_cleartext", severity=Severity.FAIL, file=rel,
                detail=f"network security config failed to parse: {exc}",
            ))
            continue
        for config in root.iter():
            if config.tag not in ("base-config", "domain-config"):
                continue
            permitted = config.get("cleartextTrafficPermitted")
            if permitted == "true":
                domains = [d.text for d in config.findall("domain")]
                scope = f"domains {domains}" if domains else "base-config (applies app-wide)"
                findings.append(Finding(
                    gate="network_cleartext", severity=Severity.FAIL, file=rel,
                    detail=f"cleartextTrafficPermitted=\"true\" for {scope}.",
                ))
    return findings

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []

    for manifest_path in find_manifests(repo_root):
        try:
            root = parse_manifest(manifest_path)
        except Exception:
            continue  # already reported by other manifest-based gates
        rel = str(manifest_path.relative_to(repo_root))
        findings += _check_internet_permission(root, rel)
        findings += _check_cleartext_application_flag(root, rel)

    findings += _check_network_security_config(repo_root)
    return findings
