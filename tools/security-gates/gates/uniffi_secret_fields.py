"""
Gate: secret-bearing string fields in the UniFFI surface.

Maps to MASWE-0003 (secrets held outside a protected/opaque type) applied to the FFI boundary specifically.

Structured-ish parsing of Rust source (full syn-based parsing is a real dependency
this gate intentionally avoids; "dependency-light" per the task brief):
brace/paren-balanced block extraction, not single-line regexes,
since multi-line definitions are the normal style in this codebase.

Five UniFFI surface shapes are scanned, since a real mobile FFI contract
uses more than just the first two (confirmed against a real submitted
contract, which used all five):

  1. `#[derive(..., uniffi::Record)]` struct fields.
  2. `#[derive(..., uniffi::Enum)]` enum variants with named (struct-like)
     fields — e.g. `Unlocked { master_secret: String }`. Enum variant
     fields have no `pub` keyword (they inherit the enum's own
     visibility), unlike struct fields, so this uses a separate
     field-matching pattern that doesn't require one.
  3. `#[uniffi::export]` / `#[uniffi::export(...)]` on a standalone
     `pub fn`.
  4. `#[uniffi::export]` / `#[uniffi::export(...)]` applied to a whole
     `impl TYPE { ... }` block — every method inside counts, without
     needing its own per-method attribute.
  5. `#[uniffi::export(with_foreign)]` applied to a `pub trait TYPE {
     ... }` — every method inside counts. Trait methods conventionally
     have no `pub` keyword either.

A method carrying its own *individual* `#[uniffi::export]` attribute
inside an otherwise-unmarked impl block is already covered by shape 3's
logic; the attribute-to-fn-header proximity match doesn't care whether
the `pub fn` happens to sit inside an impl block or not.

An earlier version of this gate only implemented shapes 1 and 3, and its
apparent handling of shape 4 was pure coincidence: a `#[uniffi::export]
impl` with exactly one short method right after the attribute happened to
fall inside the (fixed, unconditional) 200-character lookahead window the
standalone-fn logic used, so it got caught by accident. Adding one
unrelated method before the real target, or a slightly longer impl block,
made it invisible again; confirmed by direct probe before this fix, not
assumed. Shapes 2, 4, and 5 needed real structural handling,
not a wider window.

For each field or parameter identified in this way, flag it when its name
matches a secret-identifier pattern (password, secret, key, token, ssk,
credential, pwd, or master, with underscore-aware matching as defined
by SECRET_NAME) and its type (after whitespace normalization) is String,
&str, or Option<String>. Therefore, Option < String > and Option<String>
are treated as equivalent. `Vec<u8>`/`Option<Vec<u8>>` are NOT flagged:
byte buffers are allowed as an acceptable secret representation, so a
bare byte buffer already satisfies the contract on its own. A
non-primitive wrapper type (`SecretBytes`, `Zeroizing<Vec<u8>>`, ...)
is also not flagged, on the theory that a custom type is where
zeroize-on-drop and redaction-on-Debug would actually be implemented;
this gate does not verify the wrapper type itself does those things.

Known limitations: this will not catch a secret field with a non-obvious
name (e.g. `blob`, `payload`, `data`). It also cannot verify that a custom
wrapper type actually zeroizes. The enum-variant scanner and the impl/trait
method scanner both use a simple `word {` pattern / `fn word(` pattern
over already-extracted block text rather than a real grammar, so an
unusual construct (a doc comment containing literal `{`, a nested closure
defining its own inner `fn`) could in principle produce a fake extra scan pass;
harmless in practice (worst case: redundant work, not a wrong verdict)
but disclosed rather than assumed away.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Severity, iter_files

RECORD_DERIVE = re.compile(r"#\[derive\([^)]*uniffi::Record[^)]*\)\]")
ENUM_DERIVE = re.compile(r"#\[derive\([^)]*uniffi::Enum[^)]*\)\]")
EXPORT_ATTR = re.compile(r"#\[uniffi::export(?:\([^)]*\))?\]")

STRUCT_HEADER = re.compile(r"pub\s+struct\s+(\w+)")
ENUM_HEADER = re.compile(r"pub\s+enum\s+(\w+)")
FN_HEADER = re.compile(r"pub\s+fn\s+(\w+)\s*\(")
IMPL_HEADER = re.compile(r"impl(?:<[^>]*>)?\s+(\w+)")
TRAIT_HEADER = re.compile(r"pub\s+trait\s+(\w+)")
METHOD_HEADER = re.compile(r"(?:pub\s+)?fn\s+(\w+)\s*\(")
VARIANT_HEADER = re.compile(r"(\w+)\s*\{")

STRUCT_FIELD_LINE = re.compile(r"pub\s+(\w+)\s*:\s*([^,\n]+),?")
ENUM_FIELD_LINE = re.compile(r"(\w+)\s*:\s*([^,\n]+),?")
PARAM = re.compile(r"(\w+)\s*:\s*([^,()]+)")

SECRET_NAME = re.compile(
    r"password|secret|(?<![A-Za-z0-9])key(?![A-Za-z0-9])|token|credential|"
    r"(?<![A-Za-z0-9])ssk(?![A-Za-z0-9])|(?<![A-Za-z0-9])pwd(?![A-Za-z0-9])|master",
    re.IGNORECASE,
)
# Primitive or unwrapped STRING types that carry no type-level "careful
# handling" signal, flagged when paired with a secret-shaped name.
#
# Vec<u8> or Option<Vec<u8>> are DELIBERATELY NOT in this set: byte buffers
# are acceptable representation for a secret; a bare byte buffer
# already satisfies the contract on its own.
BARE_TYPES = {"String", "&str", "str", "Option<String>"}

EXPORT_LOOKAHEAD = 300

def _extract_block(text: str, start: int) -> str:
    """From a struct/enum/impl/trait/fn header's opening brace/paren,
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

def _normalize_type(type_str: str) -> str:
    """Collapse all whitespace so syntactically-equivalent Rust type
    spellings compare equal (e.g. "Option < String >" and "Option<String>").
    Rust generic syntax never requires internal whitespace for validity,
    so stripping it is always safe here; this fixes a real bug where
    a spaced-out bare-string type silently bypassed the exact-string
    BARE_TYPES membership check."""
    return re.sub(r"\s+", "", type_str)

def _flag_if_bare_secret(name: str, type_str: str) -> bool:
    if not SECRET_NAME.search(name):
        return False
    normalized = _normalize_type(type_str.strip().rstrip(";"))
    return normalized in BARE_TYPES

def _line_no(text: str, pos: int) -> int:
    return text[:pos].count("\n") + 1

def _scan_record(text: str, rel: str, findings: list[Finding]) -> None:
    for match in RECORD_DERIVE.finditer(text):
        search_start = match.end()
        struct_match = STRUCT_HEADER.search(text, search_start, search_start + 200)
        if not struct_match:
            continue
        brace_idx = _find_block_start(text, struct_match.end(), "{")
        if brace_idx is None:
            continue
        body = _extract_block(text, brace_idx)
        line_no = _line_no(text, struct_match.start())
        for field_match in STRUCT_FIELD_LINE.finditer(body):
            fname, ftype = field_match.group(1), field_match.group(2)
            if _flag_if_bare_secret(fname, ftype):
                findings.append(Finding(
                    gate="uniffi_secret_fields", severity=Severity.FAIL,
                    file=rel, line=line_no,
                    detail=(
                        f"struct {struct_match.group(1)} field "
                        f"`{fname}: {ftype.strip()}` looks secret-shaped "
                        f"by name but is a bare string type "
                        f"(String/&str/Option<String>) with no protective "
                        f"wrapper type; represent this as an explicit "
                        f"secret/opaque byte type instead."
                    ),
                ))

def _scan_enum(text: str, rel: str, findings: list[Finding]) -> None:
    for match in ENUM_DERIVE.finditer(text):
        search_start = match.end()
        enum_match = ENUM_HEADER.search(text, search_start, search_start + 200)
        if not enum_match:
            continue
        brace_idx = _find_block_start(text, enum_match.end(), "{")
        if brace_idx is None:
            continue
        enum_body = _extract_block(text, brace_idx)
        # Struct-like variants only: `VariantName { field: Type, ... }`.
        # Tuple variants (`VariantName(String)`) and unit variants
        # (`VariantName`) have no named fields to inspect and simply
        # never match this brace-requiring pattern.
        for variant_match in VARIANT_HEADER.finditer(enum_body):
            variant_name = variant_match.group(1)
            variant_brace_idx = variant_match.end() - 1
            variant_body = _extract_block(enum_body, variant_brace_idx)
            line_no = _line_no(text, brace_idx + variant_match.start())
            for field_match in ENUM_FIELD_LINE.finditer(variant_body):
                fname, ftype = field_match.group(1), field_match.group(2)
                if _flag_if_bare_secret(fname, ftype):
                    findings.append(Finding(
                        gate="uniffi_secret_fields", severity=Severity.FAIL,
                        file=rel, line=line_no,
                        detail=(
                            f"enum {enum_match.group(1)} variant "
                            f"{variant_name} field `{fname}: {ftype.strip()}` "
                            f"looks secret-shaped by name but is a bare "
                            f"string type (String/&str/Option<String>) "
                            f"with no protective wrapper type."
                        ),
                    ))

def _scan_methods_in_block(body: str, text: str, block_start_pos: int,
                            container_kind: str, container_name: str,
                            rel: str, findings: list[Finding]) -> None:
    for method_match in METHOD_HEADER.finditer(body):
        mname = method_match.group(1)
        paren_idx = _find_block_start(body, method_match.end() - 1, "(")
        if paren_idx is None:
            continue
        params = _extract_block(body, paren_idx)
        line_no = _line_no(text, block_start_pos + method_match.start())
        for param_match in PARAM.finditer(params):
            pname, ptype = param_match.group(1), param_match.group(2)
            if pname == "self":
                continue
            if _flag_if_bare_secret(pname, ptype):
                findings.append(Finding(
                    gate="uniffi_secret_fields", severity=Severity.FAIL,
                    file=rel, line=line_no,
                    detail=(
                        f"{container_kind} {container_name} method {mname} "
                        f"parameter `{pname}: {ptype.strip()}` looks "
                        f"secret-shaped by name but is a bare string type "
                        f"(String/&str/Option<String>)."
                    ),
                ))

def _scan_export_attrs(text: str, rel: str, findings: list[Finding]) -> None:
    for match in EXPORT_ATTR.finditer(text):
        search_start = match.end()
        end = search_start + EXPORT_LOOKAHEAD

        fn_match = FN_HEADER.search(text, search_start, end)
        impl_match = IMPL_HEADER.search(text, search_start, end)
        trait_match = TRAIT_HEADER.search(text, search_start, end)
        candidates = [m for m in (fn_match, impl_match, trait_match) if m]
        if not candidates:
            continue
        first = min(candidates, key=lambda m: m.start())

        if first is fn_match:
            paren_idx = _find_block_start(text, fn_match.end() - 1, "(")
            if paren_idx is None:
                continue
            params = _extract_block(text, paren_idx)
            line_no = _line_no(text, fn_match.start())
            for param_match in PARAM.finditer(params):
                pname, ptype = param_match.group(1), param_match.group(2)
                if pname == "self":
                    continue
                if _flag_if_bare_secret(pname, ptype):
                    findings.append(Finding(
                        gate="uniffi_secret_fields", severity=Severity.FAIL,
                        file=rel, line=line_no,
                        detail=(
                            f"exported fn {fn_match.group(1)} parameter "
                            f"`{pname}: {ptype.strip()}` looks secret-shaped "
                            f"by name but is a bare string type "
                            f"(String/&str/Option<String>)."
                        ),
                    ))
        elif first is impl_match:
            brace_idx = _find_block_start(text, impl_match.end(), "{")
            if brace_idx is None:
                continue
            body = _extract_block(text, brace_idx)
            _scan_methods_in_block(body, text, brace_idx, "impl",
                                    impl_match.group(1), rel, findings)
        elif first is trait_match:
            brace_idx = _find_block_start(text, trait_match.end(), "{")
            if brace_idx is None:
                continue
            body = _extract_block(text, brace_idx)
            _scan_methods_in_block(body, text, brace_idx, "trait",
                                    trait_match.group(1), rel, findings)

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    for path in iter_files(repo_root, ".rs"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        rel = str(path.relative_to(repo_root))
        _scan_record(text, rel, findings)
        _scan_enum(text, rel, findings)
        _scan_export_attrs(text, rel, findings)
    return findings
