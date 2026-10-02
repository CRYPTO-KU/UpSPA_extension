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

  - Logging calls checked: Log.d/i/w/e/v(...) (Android); println!/eprintln!/
    dbg! (Rust); bare println(...) and System.out.println(...)/System.err.
    println(...) (ordinary JVM stdout/stderr; Kotlin/Java code that bypasses
    Android's Log API entirely still needs to be caught).
  - Persistence calls checked: SharedPreferences .putString/.edit(),
    Rust std::fs::write / File::create (a plaintext-file write is exactly
    the pattern the chrome.storage.session bug matches, ported to what a
    Kotlin/Rust rewrite of the same mistake would look like).

Secret identifiers matched: password, pwd, ssk, Rsp, K0, Rlsj, masterKey,
signing_key, toprf, private_key; same list used in the browser-extension
checks, for consistency across the whole security-gates effort.

`password`/`pwd`/`ssk`/`key` are matched as plain substrings, not
word-bounded (`Rsp`/`K0`/`Rlsj` keep `\b` boundaries, since those are
distinctive protocol symbols unlikely to appear embedded in an unrelated
identifier). This is a deliberate and disclosed trade-off, not an oversight:
a strict `\bpassword\b` silently fails to match `masterPassword` or
`userPwd`; camelCase compounds have no word-boundary character between
the two halves, the identical failure mode `uniffi_secret_fields`
`SECRET_NAME` had for underscore-separated names, fixed the same way
there. Rather than build a camelCase-aware boundary, this gate accepts
the wider substring match and its noisier false-positive risk (a stray
"keyboard" reference would now also trigger the window check); for a
security gate, a missed real secret is a worse outcome than an extra
line a reviewer has to glance at and dismiss.

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
    r"Log\.(d|i|w|e|v)\s*\(|println!|eprintln!|dbg!|log::(info|debug|warn|error|trace)|"
    r"\bprintln\s*\(|System\.(out|err)\.println\s*\("
)
PERSIST_CALL = re.compile(
    r"\.putString\s*\(|\.edit\s*\(\s*\)|std::fs::write|File::create"
)
SECRET_IDENTIFIER = re.compile(
    r"password|pwd|ssk|\bRsp\b|\bK0\b|\bRlsj\b|masterkey|master_key|"
    r"signing[_ ]?key|toprf|private[_ ]?key|key",
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
                    detail=(
                        f"rule=secret-identifier-proximity: a secret-shaped "
                        f"identifier appears within {WINDOW_LINES} line(s) of "
                        f"this call; please see the file/line for the actual "
                        f"expression rather than reproducing it here."
                    ),
                ))
    return findings

def run(repo_root: Path) -> list[Finding]:
    return (
        _scan(repo_root, LOG_CALL, "secret_logging")
        + _scan(repo_root, PERSIST_CALL, "secret_logging")
    )
