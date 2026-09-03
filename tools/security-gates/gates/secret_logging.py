"""
Gate: suspicious secret logging or persistence.

Maps to:
  - MASWE-0005 (Insertion of Sensitive Data into Logs)
  - MASWE-0003/0016 (secrets outside platform keystore / persisted without protection).

This is the Kotlin/Rust port of the browser-extension log-leakage and
persisted-secrets checks from the earlier research doc's evidence pack;
same design (logging-call detection + secret-identifier proximity match,
conservative regex, not a taint analysis), retargeted at this repo's
actual logging and persistence idioms:

  - Logging calls checked: Log.d/i/w/e/v(...), println!/eprintln!/dbg! (Rust).
  - Persistence calls checked: SharedPreferences .putString/.edit(),
    Rust std::fs::write / File::create (a plaintext-file write is exactly
    the pattern the chrome.storage.session bug matches, ported to what a
    Kotlin/Rust rewrite of the same mistake would look like).

Secret identifiers matched: password, pwd, ssk, Rsp, K0, Rlsj, masterKey,
signing_key, toprf, private_key; same list used in the browser-extension
checks, for consistency across the whole security-gates effort.

Known, disclosed limitation (carried over from the earlier work):
This is a literal call-site pattern match. A secret value passed through an
intermediate variable or a small wrapper function before reaching a logging
or persistence call will NOT be caught; this is exactly the blind spot the
earlier browser-extension version of this check was shown to have (it missed
the plaintext-session-password bug because that code went through a
getEphemeralStorage() wrapper). This gate has the same shape of blind spot
for the same reason, and is a merge-time static gate, not a substitute
for a dynamic or runtime check before a production release.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Severity, iter_files

LOG_CALL = re.compile(
    r"Log\.(d|i|w|e|v)\s*\(|println!|eprintln!|dbg!|log::(info|debug|warn|error|trace)"
)
PERSIST_CALL = re.compile(
    r"\.putString\s*\(|\.edit\s*\(\s*\)|std::fs::write|File::create"
)
SECRET_IDENTIFIER = re.compile(
    r"\bpassword\b|\bpwd\b|\bssk\b|\bRsp\b|\bK0\b|\bRlsj\b|masterKey|master_key|"
    r"signing[_ ]?key|\btoprf\b|private[_ ]?key",
    re.IGNORECASE,
)

WINDOW_LINES = 3

def _scan(repo_root: Path, call_pattern: re.Pattern, gate_label: str) -> list[Finding]:
    findings = []
    for path in iter_files(repo_root, ".kt", ".java", ".rs"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        lines = text.splitlines()
        rel = str(path.relative_to(repo_root))
        for i, line in enumerate(lines):
            if not call_pattern.search(line):
                continue
            lo = max(0, i - WINDOW_LINES)
            hi = min(len(lines), i + WINDOW_LINES + 1)
            window = "\n".join(lines[lo:hi])
            if SECRET_IDENTIFIER.search(window):
                findings.append(Finding(
                    gate=gate_label, severity=Severity.FAIL,
                    file=rel, line=i + 1,
                    detail=f"{line.strip()[:150]}",
                ))
    return findings

def run(repo_root: Path) -> list[Finding]:
    return (
        _scan(repo_root, LOG_CALL, "secret_logging")
        + _scan(repo_root, PERSIST_CALL, "secret_logging")
    )
