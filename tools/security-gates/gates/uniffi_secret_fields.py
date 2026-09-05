"""
Gate: secret-bearing string fields in the UniFFI surface.

Maps to MASWE-0003 (secrets held outside a protected/opaque type) applied
to the FFI boundary specifically. This is the one gate whose real target
doesn't exist yet at the moment: replacing the current bootstrap-only 
#[uniffi::export]` surface (crates/upspa-mobile-ffi) with a versioned
command/event/effect contract, and specifies "represent secret values as
byte buffers or explicit secret types, not ordinary strings" as a
requirement of that work. This gate is that requirement, made executable and
run against whatever lands on the branch, including correctly passing
against the current template-only surface, which has no secret fields at all.

Structured parsing of Rust source (full syn-based parsing is a real dependency
this gate intentionally avoids): a two-pass regex approach.

  - Pass 1: find every `#[derive(..., uniffi::Record)]` struct and every
    `#[uniffi::export]` function, and extract its field list or parameter
    and return types via brace/paren matching (not a single-line regex,
    since multi-line struct definitions are the normal style in this codebase).

  - Pass 2: for every record field or exported-function parameter found,
    flag it if:
    - the name matches a secret-identifier pattern (password, secret, key,
      token, ssk, credential, ...), AND
    - the type is `String`, `&str`, or `Option<String>`; i.e. an ordinary
      string with no type-level signal that this value needs special
      handling. `Vec<u8>`/`Option<Vec<u8>>` are not flagged: byte buffers
      are allowed as an acceptable secret representation, so a bare byte
      buffer already satisfies the rule. A field named `session_token`
      typed as `SecretBytes` or `Zeroizing<Vec<u8>>` or any other
      non-primitive wrapper type is also not flagged, on the theory that
      a custom type is where zeroize-on-drop and redaction-on-Debug would
      actually be implemented; this gate does not verify that the wrapper
      type itself does those things.

Known limitation: this will not catch a secret field with a non-obvious
name (e.g. `blob`, `payload`, `data`) that nonetheless carries secret
material; name-based detection is inherently incomplete. It also cannot
verify that a custom wrapper type actually zeroizes; it only credits any
non-primitive type as "probably handled elsewhere" and defers that
verification to code review.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Severity, iter_files

RECORD_DERIVE = re.compile(r"#\[derive\([^)]*uniffi::Record[^)]*\)\]")
EXPORT_FN = re.compile(r"#\[uniffi::export\]")
STRUCT_HEADER = re.compile(r"pub\s+struct\s+(\w+)")
FN_HEADER = re.compile(r"pub\s+fn\s+(\w+)\s*\(")

FIELD_LINE = re.compile(r"pub\s+(\w+)\s*:\s*([^,\n]+),?")
PARAM = re.compile(r"(\w+)\s*:\s*([^,()]+)")

SECRET_NAME = re.compile(
    r"password|secret|(?<![A-Za-z0-9])key(?![A-Za-z0-9])|token|credential|"
    r"(?<![A-Za-z0-9])ssk(?![A-Za-z0-9])|(?<![A-Za-z0-9])pwd(?![A-Za-z0-9])|master",
    re.IGNORECASE,
)
# Primitive or unwrapped STRING types that carry no type-level "careful handle" signal.
# A type is flagged only if it reduces to exactly one of these.
# Vec<u8>/Option<Vec<u8>> are deliberately NOT here; byte buffers are acceptable form
# for secret representation, so a bare Vec<u8> already satisfies the contract on its own.
BARE_TYPES = {"String", "&str", "str", "Option<String>"}

def _extract_block(text: str, start: int) -> str:
    """From a struct/fn header's opening brace/paren,
    return the balanced block contents."""
    open_char = text[start]
    close_char = {"{": "}", "(": ")"}[open_char]
    depth = 0
    i = start
    while i < len(text):
        if text[i] == open_char:
            depth += 1
        elif text[i] == close_char:
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return text[start + 1:]

def _find_block_start(text: str, from_idx: int, open_char: str) -> int | None:
    idx = text.find(open_char, from_idx)
    return idx if idx != -1 else None

def _flag_if_bare_secret(name: str, type_str: str) -> bool:
    type_str = type_str.strip().rstrip(";").strip()
    if not SECRET_NAME.search(name):
        return False
    return type_str in BARE_TYPES

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []

    for path in iter_files(repo_root, ".rs"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        rel = str(path.relative_to(repo_root))

        # Records
        for match in RECORD_DERIVE.finditer(text):
            header_search_start = match.end()
            struct_match = STRUCT_HEADER.search(text, header_search_start, header_search_start + 200)
            if not struct_match:
                continue
            brace_idx = _find_block_start(text, struct_match.end(), "{")
            if brace_idx is None:
                continue
            body = _extract_block(text, brace_idx)
            line_no = text[:struct_match.start()].count("\n") + 1
            for field_match in FIELD_LINE.finditer(body):
                fname, ftype = field_match.group(1), field_match.group(2)
                if _flag_if_bare_secret(fname, ftype):
                    findings.append(Finding(
                        gate="uniffi_secret_fields", severity=Severity.FAIL,
                        file=rel, line=line_no,
                        detail=(
                            f"struct {struct_match.group(1)} field "
                            f"`{fname}: {ftype.strip()}` looks secret-shaped by name "
                            f"but is a bare String type (String/&str/Option<String>) "
                            f"with no protective wrapper type; represent this as "
                            f"an explicit secret/opaque byte type instead."
                        ),
                    ))

        # Exported functions
        for match in EXPORT_FN.finditer(text):
            header_search_start = match.end()
            fn_match = FN_HEADER.search(text, header_search_start, header_search_start + 200)
            if not fn_match:
                continue
            paren_idx = _find_block_start(text, fn_match.end() - 1, "(")
            if paren_idx is None:
                continue
            params = _extract_block(text, paren_idx)
            line_no = text[:fn_match.start()].count("\n") + 1
            for param_match in PARAM.finditer(params):
                pname, ptype = param_match.group(1), param_match.group(2)
                if pname in ("self",):
                    continue
                if _flag_if_bare_secret(pname, ptype):
                    findings.append(Finding(
                        gate="uniffi_secret_fields", severity=Severity.FAIL,
                        file=rel, line=line_no,
                        detail=(
                            f"exported fn {fn_match.group(1)} parameter "
                            f"`{pname}: {ptype.strip()}` looks secret-shaped by name "
                            f"but is a bare string type (String/&str/Option<String>)."
                        ),
                    ))

    return findings
