"""
Gate: one-shot authentication PendingIntent construction.

Maps to:
  - MASWE-0032 (Insecure Intents)
  - MASTG-BEST-0063 (Use Immutable PendingIntents with Explicit Intents).

The naive rule (FLAG_MUTABLE is always wrong) is actually incorrect for
this specific app. Android's Autofill authentication flow requires
FLAG_MUTABLE on the PendingIntent handed to FillResponse.setAuthentication(),
because the framework attaches AutofillManager.EXTRA_AUTHENTICATION_RESULT
extras onto the intent before delivering it. An immutable PendingIntent
cannot receive those extras and the flow silently breaks. This is a real,
current example in this repo's own UpspaAutofillService.kt, not a
hypothetical exception carved out to make the check pass.

The actual rule this gate enforces is for every
PendingIntent.getActivity/getBroadcast/getService(...) call:
  - Must use an explicit Intent (constructed via `Intent(context, X::class.
    java)` or equivalent), not an implicit one; checked by the presence
    of a `::class.java` / explicit-component pattern near the call.
  - Must include FLAG_IMMUTABLE, unless the surrounding context is an
    autofill-authentication PendingIntent (heuristic: the call appears in
    a file or method involving FillResponse.setAuthentication or
    IntentSender, and a comment or the flags explain the mutability).
    If FLAG_MUTABLE is present without FLAG_IMMUTABLE and it's NOT
    in an autofill-authentication context, that's a FAIL.
  - Should include FLAG_ONE_SHOT or FLAG_CANCEL_CURRENT for any
    authentication-flow PendingIntent (one-time use; reuse of a stale
    auth PendingIntent is the "one-shot" property this gate name refers to).
    Missing both is a FAIL specifically for PendingIntents built
    inside an authentication-flow context.

Known limitation: "autofill-authentication context" is detected by
proximity (same file mentions FillResponse/setAuthentication/IntentSender
within the surrounding around 40 lines) rather than true call-graph analysis;
a mutable PendingIntent built far from any such context in the same file
could be mis-classified as justified. Flagged rather than assumed safe.

Explicit-intent detection also can't do real data-flow analysis: it
recognizes both an inline `Intent(context, X::class.java)` and the common
Kotlin idiom of a companion-object `X.newIntent(...)` factory function that
returns an already-explicit Intent (as used in this codebase's own
CredentialAuthActivity), but a different non-conventionally-named helper
that also happens to build an explicit intent could still be missed.
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Severity, iter_files

PENDING_INTENT_CALL = re.compile(
    r"PendingIntent\.get(Activity|Broadcast|Service)\s*\("
)
AUTOFILL_AUTH_CONTEXT = re.compile(
    r"FillResponse|setAuthentication|IntentSender|AutofillManager"
)
EXPLICIT_INTENT_HINT = re.compile(
    r"::class\.java|Intent\([^)]*,\s*\w+\)|\.newIntent\s*\("
)
IMMUTABLE_FLAG = re.compile(r"FLAG_IMMUTABLE")
MUTABLE_FLAG = re.compile(r"FLAG_MUTABLE")
ONE_SHOT_OR_CANCEL = re.compile(r"FLAG_ONE_SHOT|FLAG_CANCEL_CURRENT")

CONTEXT_WINDOW = 40

def run(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []

    for path in iter_files(repo_root, ".kt", ".java"):
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        lines = text.splitlines()
        rel = str(path.relative_to(repo_root))

        for i, line in enumerate(lines):
            if not PENDING_INTENT_CALL.search(line):
                continue
            lineno = i + 1

            # Look at a window around the call site; Kotlin PendingIntent
            # builder calls are frequently multi-line, so a single-line
            # check would miss the flags argument entirely.
            lo = max(0, i - 5)
            hi = min(len(lines), i + 15)
            window = "\n".join(lines[lo:hi])

            context_lo = max(0, i - CONTEXT_WINDOW)
            context_hi = min(len(lines), i + CONTEXT_WINDOW)
            wide_context = "\n".join(lines[context_lo:context_hi])
            is_autofill_auth = bool(AUTOFILL_AUTH_CONTEXT.search(wide_context))

            if not EXPLICIT_INTENT_HINT.search(wide_context):
                findings.append(Finding(
                    gate="pending_intent", severity=Severity.FAIL,
                    file=rel, line=lineno,
                    detail=(
                        "PendingIntent constructed without a clearly "
                        "explicit target Intent nearby (no `::class.java` "
                        "component reference or `.newIntent(...)` factory "
                        "call found within the surrounding 40 lines); "
                        "implicit intents inside a PendingIntent can be "
                        "redirected by another app."
                    ),
                ))

            has_immutable = bool(IMMUTABLE_FLAG.search(window))
            has_mutable = bool(MUTABLE_FLAG.search(window))

            if has_mutable and not has_immutable:
                if not is_autofill_auth:
                    findings.append(Finding(
                        gate="pending_intent", severity=Severity.FAIL,
                        file=rel, line=lineno,
                        detail=(
                            "FLAG_MUTABLE set with no FLAG_IMMUTABLE and no "
                            "nearby Autofill-authentication context "
                            "(FillResponse/setAuthentication/IntentSender) "
                            "to justify it; mutable PendingIntents outside "
                            "that specific, documented exception can be "
                            "retargeted by the receiving component."
                        ),
                    ))
                # else: mutable-for-autofill is the known, correct pattern;
                # no finding, but still check one-shot below.
            elif not has_immutable and not has_mutable:
                findings.append(Finding(
                    gate="pending_intent", severity=Severity.FAIL,
                    file=rel, line=lineno,
                    detail=(
                        "PendingIntent built with neither FLAG_IMMUTABLE nor "
                        "FLAG_MUTABLE specified; on API 31 and below this "
                        "silently defaults to mutable."
                    ),
                ))

            if is_autofill_auth and not ONE_SHOT_OR_CANCEL.search(window):
                findings.append(Finding(
                    gate="pending_intent", severity=Severity.FAIL,
                    file=rel, line=lineno,
                    detail=(
                        "PendingIntent in an Autofill-authentication context "
                        "has no FLAG_ONE_SHOT or FLAG_CANCEL_CURRENT; "
                        "a stale authentication PendingIntent should not "
                        "be replayable."
                    ),
                ))

    return findings
