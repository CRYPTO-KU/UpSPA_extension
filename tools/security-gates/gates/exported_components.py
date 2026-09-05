"""
Gate: exported Android components and binding permissions.

Maps to MASWE-0018 (Lack of Authentication/Authorization on App Components).

Rule, structurally parsed from AndroidManifest.xml (not regex;
this file is real XML and ElementTree handles namespaces correctly):

  For every <activity>, <service>, <receiver>, <provider>:
    - exported="false" -> fine, no finding.
    - exported not set at all:
        * FAIL if the component has an <intent-filter> child, because an
          unset `exported` with a filter present defaults to exported=true
          on the platform (the classic silent-footgun case); same risk as
          an explicit true, so it must not be reported as merely INFO.
        * INFO otherwise (defaults to false with no filter; worth surfacing
          once so a future intent-filter addition doesn't silently flip
          this, but not a FAIL on its own).
    - exported="true":
        * OK (no FAIL) if the component name matches a known system-bound
          service contract (AutofillService / CredentialProviderService)
          which must be exported and *must* carry the matching BIND_*
          permission; that permission requirement is itself checked
          explicitly below rather than assumed satisfied just because
          the name matches.
        * OK (no FAIL) if the declared android:permission is itself
          verified as meaningfully restrictive: a <permission> element
          declared in the same manifest with that exact name and a
          signature-tier protectionLevel (signature / signatureOrSystem /
          knownSigner / signature|privileged). Declaring any permission
          string used to be treated as sufficient here, which was a real bug;
          an exported component "protected" only by a normal permission
          every app already holds (e.g. INTERNET) provided no real
          caller authorization.
        * OK (no FAIL) for an <activity> whose intent-filter is exactly the
          launcher entry point (action MAIN + category LAUNCHER); that is
          the one legitimate exported-with-no-permission case, since the
          OS/launcher needs to be able to start it with no prior handshake.
        * FAIL otherwise: exported with no permission, or exported with a
          permission that isn't a recognized system-bound contract and
          isn't locally verified as signature-level.

Known limitation:
    - This does not evaluate <intent-filter> action/category values for
      correctness (e.g. whether BIND_AUTOFILL_SERVICE actually pairs
      with the android.service.autofill.AutofillService action) beyond
      a name check. A mismatch there would need a semantic Android-services
      reference table, not just manifest inspection; flagged here rather
      than silently assumed correct.
    - The protection-level check only recognizes a permission as adequately
      restrictive if this app's own manifest declares it locally with
      a signature-tier protectionLevel. A genuinely signature-level platform
      permission that isn't one of the two names in the system-bound-service
      allowlist (e.g. some other AOSP BIND_* permission) would still FAIL here,
      because there's no local <permission> declaration to verify against;
      deliberately, since trusting an unenumerated platform permission
      by name alone is exactly the class of bug this fix closes.
      Extending the allowlist is the correct fix if a real, legitimate case
      like that comes up, not loosening this check.
"""
from __future__ import annotations

from pathlib import Path

from .common import Finding, Severity, android_attr, find_manifests, parse_manifest

COMPONENT_TAGS = ("activity", "service", "receiver", "provider")

# Components that are legitimately exported=true by platform contract, and
# the BIND_* permission each one is required to declare when exported.
SYSTEM_BOUND_SERVICES = {
    "autofill": "android.permission.BIND_AUTOFILL_SERVICE",
    "credentials": "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE",
}

# Same signing-cert-restricted tier under different API-level naming;
# not four distinct levels worth telling apart for this check.
SIGNATURE_TIER_PROTECTION_LEVELS = {
    "signature", "signatureOrSystem", "knownSigner", "signature|privileged",
}

# Names of custom permissions this manifest declares with a
# signature-tier protectionLevel; i.e., permissions that the app
# actually controls, rather than permissions that any app
# can obtain simply by requesting them.
def _signature_level_permissions(root) -> set[str]:
    verified = set()
    for perm in root.iter("permission"):
        name = android_attr(perm, "name")
        level = android_attr(perm, "protectionLevel")
        if name and level in SIGNATURE_TIER_PROTECTION_LEVELS:
            verified.add(name)
    return verified

def _has_intent_filter(element) -> bool:
    return element.find("intent-filter") is not None

def _is_launcher_activity(element) -> bool:
    """The app's entry point (MAIN action + LAUNCHER category) is supposed
    to be exported=true with no permission; that's how the OS/launcher is
    able to start it at all. This is the one legitimate case of
    exported=true-with-no-permission that isn't a finding."""
    for intent_filter in element.findall("intent-filter"):
        actions = {android_attr(a, "name") for a in intent_filter.findall("action")}
        categories = {android_attr(c, "name") for c in intent_filter.findall("category")}
        if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
            return True
    return False

def _matches_system_bound_service(name: str) -> str | None:
    for keyword, permission in SYSTEM_BOUND_SERVICES.items():
        if keyword in name.lower():
            return permission
    return None

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []

    for manifest_path in find_manifests(repo_root):
        try:
            root = parse_manifest(manifest_path)
        except Exception as exc:  # malformed XML is itself a finding, not a crash
            findings.append(Finding(
                gate="exported_components", severity=Severity.FAIL,
                file=str(manifest_path), detail=f"manifest failed to parse: {exc}",
            ))
            continue

        rel = str(manifest_path.relative_to(repo_root))
        verified_permissions = _signature_level_permissions(root)

        for tag in COMPONENT_TAGS:
            for element in root.iter(tag):
                name = android_attr(element, "name") or "(unnamed)"
                exported = android_attr(element, "exported")
                permission = android_attr(element, "permission")
                has_filter = _has_intent_filter(element)

                if exported == "false":
                    continue

                if exported is None:
                    if has_filter:
                        findings.append(Finding(
                            gate="exported_components", severity=Severity.FAIL,
                            file=rel,
                            detail=(
                                f"<{tag} android:name=\"{name}\"> has an "
                                f"<intent-filter> but no explicit android:exported; "
                                f"this defaults to exported=true on-device, which "
                                f"is easy to miss in review. Set exported explicitly."
                            ),
                        ))
                    else:
                        findings.append(Finding(
                            gate="exported_components", severity=Severity.INFO,
                            file=rel,
                            detail=(
                                f"<{tag} android:name=\"{name}\"> has no explicit "
                                f"android:exported (defaults to false; fine for now, "
                                f"but set it explicitly so a later intent-filter "
                                f"addition can't silently flip this)."
                            ),
                        ))
                    continue

                # exported == "true" from here down.
                required_permission = _matches_system_bound_service(name)
                if required_permission:
                    if permission == required_permission:
                        continue  # correctly bound system service, no finding
                    findings.append(Finding(
                        gate="exported_components", severity=Severity.FAIL,
                        file=rel,
                        detail=(
                            f"<{tag} android:name=\"{name}\"> looks like a "
                            f"system-bound service, but does not declare "
                            f"android:permission=\"{required_permission}\" "
                            f"(found: {permission!r}); an exported service "
                            f"matching this framework contract without the "
                            f"matching BIND_* permission can be invoked by "
                            f"any other app on the device."
                        ),
                    ))
                    continue

                if permission and permission in verified_permissions:
                    continue  # exported, protected by a locally-verified signature-level permission
                
                if tag == "activity" and _is_launcher_activity(element):
                    continue  # the app's entry point: correctly exported, no permission needed
                
                if permission:
                    findings.append(Finding(
                        gate="exported_components", severity=Severity.FAIL,
                        file=rel,
                        detail=(
                            f"<{tag} android:name=\"{name}\"> is exported=\"true\" "
                            f"with android:permission=\"{permission}\", but that "
                            f"permission isn't a recognized system-bound contract "
                            f"and isn't declared locally with a signature-tier "
                            f"protectionLevel; it provides no real caller authorization "
                            f"if it's a normal/dangerous permission any app can already hold."
                        ),
                    ))
                    continue
                
                findings.append(Finding(
                    gate="exported_components", severity=Severity.FAIL,
                    file=rel,
                    detail=(
                        f"<{tag} android:name=\"{name}\"> is exported=\"true\" "
                        f"with no android:permission; reachable by any other app "
                        f"installed on the device with no authorization check."
                    ),
                ))

    return findings
