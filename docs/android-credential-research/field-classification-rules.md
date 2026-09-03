# Field-Classification Rules — The 3-Tier Hypothesis on Android

Status: **DRAFT** (research topic 3, output asset "Field-classification rules"). This
documents the model **as implemented** in
[`FieldClassifier.kt`](../../research/android-credential-lab/autofill-provider/src/main/java/com/upspa/research/provider/FieldClassifier.kt)
— it is the hypothesis under test, not a validated result. Phase 3 experiments either
confirm each rule or force a revision here (Section 9 lists what would falsify what).

## 0. Lineage and design constraints

The model is ported from the browser-extension architecture
([emirhan-universal-autofill-architecture.md](../emirhan-universal-autofill-architecture.md)),
which bets on three durable ideas: **standards first, structure over text, language
independence**. The Android port adds two hard constraints of its own:

1. **Secret hygiene.** The classifier never reads `ViewNode.getText()`. User-typed values
   must not enter the corpus, the logs, or any derived artifact. Only developer-authored
   metadata (resource ids, hints, HTML attributes) is inspected.
2. **Asymmetric error cost.** Filling a credential into a search box is a credential leak;
   missing a login field is an inconvenience. Every rule below is tuned to prefer false
   negatives over false positives — this is why poison terms *veto* rather than merely
   down-weight.

## 1. Signal inventory (what `AssistStructure` actually gives us)

| `ViewNode` signal | Used? | Tier | Notes |
| --- | --- | --- | --- |
| `autofillHints` | Yes | 1 | Developer-declared, spec-defined tokens. Authoritative. |
| `htmlInfo` attributes (`autocomplete`, `type`, `name`, `id`, `placeholder`, `label`) | Yes | 1 (`autocomplete`) / 2 (rest) | Only present for WebView-backed nodes; the Android analogue of the extension's DOM view. |
| `inputType` | Yes | 2 | Password variations are the strongest structural signal on native views. |
| `idEntry` (resource id) | Yes | 2 | Developer-chosen and **never localized** — stronger than web class names in practice. |
| `hint` | Yes | 2 | Localized! English-only regexes will miss e.g. "Kullanıcı adı"/"Şifre" (Section 9). |
| `contentDescription` | Yes | 2 | Accessibility text; same localization caveat. |
| `autofillType` | Yes | candidate filter | Only `AUTOFILL_TYPE_TEXT` nodes are candidates. |
| `visibility` | Yes | candidate filter | Only `View.VISIBLE` nodes; `INVISIBLE` and `GONE` are rejected (hidden-fields exfiltration, ledger A-1). |
| `isEnabled` | Yes | candidate filter | Disabled inputs are rejected — they cannot legitimately receive input, so filling them is a phantom fill (security audit 2026-08-22). |
| `text` | **Never** | — | Secret hygiene (constraint 1). |
| Sibling label `TextView`s | Not yet | — | Known gap: Android has no `<label for=...>`; associating a preceding TextView is a candidate refinement, deliberately deferred (localized text, weak signal). |

## 2. Candidate selection

A node becomes a classification candidate iff it has an `autofillId`, reports
`AUTOFILL_TYPE_TEXT`, is **visible** (`View.VISIBLE` — not `INVISIBLE`, not `GONE`), and is
**enabled**. This one filter uniformly admits native `EditText`s, WebView HTML inputs, and
custom-view virtual children (the custom-view fixture proves the third path), while
excluding buttons, labels, and decorative nodes. All signals come from
`AssistStructure.ViewNode` (ledger S-9).

```kotlin
if (autofillId != null &&
    node.autofillType == View.AUTOFILL_TYPE_TEXT &&
    node.visibility == View.VISIBLE &&
    node.isEnabled
) { /* candidate */ }
```

The visibility and enabled gates are security controls, not conveniences: Aonzo et al.
(CCS 2018, ledger A-1) showed password managers filling **hidden fields**, turning autofill
into a stealthy credential-exfiltration channel. A field the user cannot see or interact
with must never receive a value.

Caveat discovered by design review (to verify in EXP-003): **Compose screens produce no such
nodes at all** on Compose 1.7 unless fields are explicitly wired — candidate selection cannot
fix what the toolkit never reports.

## 3. Tier 1 — Authoritative signals (confidence 1.0, short-circuit)

When a developer declares intent, we trust it and stop. Two sources, checked in order:

**1a. Platform `autofillHints`:**

| Hint token | Role |
| --- | --- |
| `username` (`View.AUTOFILL_HINT_USERNAME`) | USERNAME |
| `emailAddress` (`View.AUTOFILL_HINT_EMAIL_ADDRESS`) | EMAIL |
| `password` (`View.AUTOFILL_HINT_PASSWORD`) | PASSWORD_CURRENT |
| `newPassword` (`HintConstants.AUTOFILL_HINT_NEW_PASSWORD`) | PASSWORD_NEW |
| `newUsername` (`HintConstants.AUTOFILL_HINT_NEW_USERNAME`) | USERNAME |
| `smsOTPCode` (`HintConstants.AUTOFILL_HINT_SMS_OTP`) | OTP |

Note: `newPassword`/`newUsername`/`smsOTPCode` are androidx `HintConstants` *conventions*,
not platform-enforced tokens — real apps may or may not use them (worth quantifying in the
Phase 3 real-app survey).

**1b. HTML `autocomplete` tokens** (WebView nodes via `htmlInfo`; the WHATWG autofill
vocabulary the browser engine already trusts, ledger S-6):

| Token | Role |
| --- | --- |
| `username` | USERNAME |
| `email` | EMAIL |
| `current-password` | PASSWORD_CURRENT |
| `new-password` | PASSWORD_NEW |
| `one-time-code` | OTP |

Tier 1 exists so that **spec-compliant apps are handled perfectly and cheaply**, and so that
the fixtures' "hinted" switch gives every experiment a Tier 1 control arm against the same
layout.

## 4. Tier 2 — Weighted attribute heuristics with poison veto (confidence 0.6)

Runs only when Tier 1 said nothing.

**Corpus.** Developer-authored metadata only, joined into one string:
`idEntry | hint | contentDescription | html(name, id, placeholder, label)`.

**Step 1 — Poison veto (evaluated first, terminal).** If the corpus matches any poison
term, the field is left UNKNOWN regardless of anything else:

| Poison pattern | Rationale |
| --- | --- |
| `search` | The classic false positive: search bars sit at the top of login-like screens. |
| `captcha` | Text field adjacent to credentials, never fillable. |
| `coupon`, `promo`, `gift` | Checkout fields that co-occur with account fields. |
| `card.?number`, `cvv`, `cvc`, `expir` | Payment fields — filling credentials here is a leak; payment autofill is explicitly out of UpSPA scope. |
| `amount`, `city`, `zip`, `postal`, `street`, `address` | Address/checkout noise. |

**Step 2 — Password detection (structural, not textual).** A field is a password input if
`inputType` carries any password variation (`textPassword`, `textWebPassword`,
`textVisiblePassword`, `numberPassword`) or its HTML `type` is `password`. Password fields
are then split current-vs-new by corpus markers (`current|old|existing` vs
`new|confirm|repeat|again|retype|verify`), defaulting to PASSWORD_CURRENT and leaving the
final word to Tier 3.

**Step 3 — Identifier patterns**, in precedence order (OTP before email before username,
so `"email verification code"` classifies as OTP, not EMAIL):

| Pattern | Role |
| --- | --- |
| `otp`, `one.?time`, `verification.?code`, `2fa`, `totp` | OTP |
| `e.?mail` | EMAIL |
| `user`, `login`, `account`, `nick`, `identifier`, `member` | USERNAME |

## 5. Tier 3 — Password topology (confidence 0.8, never overrides Tier 1)

Screen *intent* is derived from the relational structure of password fields — structure is
language-independent, so this tier works identically on an English fixture and a localized
banking app. Decision table as implemented:

| Passwords | Identifiers | "current" marker | Screen intent | Reassignments |
| --- | --- | --- | --- | --- |
| 0 | 0 | — | UNKNOWN | — |
| 0 | ≥1 | — | LOGIN | — (identifier-first / split-login step 1) |
| 1 (current) | any | — | LOGIN | positional-username fallback (below) |
| 1 (new) | any | — | REGISTRATION | — |
| 2 | any | no | REGISTRATION | both promoted to PASSWORD_NEW (a confirm field is a *confirmation*, not a second credential) |
| 2 | any | yes | PASSWORD_CHANGE | marked/first → PASSWORD_CURRENT, rest → PASSWORD_NEW |
| ≥3 | any | — | PASSWORD_CHANGE | first-or-marked → PASSWORD_CURRENT, rest → PASSWORD_NEW |

**Positional-username fallback.** When exactly one password exists and *no* identifier was
recognized, the nearest **preceding** candidate that is unclassified, non-password, and
non-poisoned is promoted to USERNAME:

```kotlin
if (passwords.size == 1 && identifiers.isEmpty()) {
    val passwordIndex = fields.indexOf(passwords[0])
    fields.subList(0, passwordIndex)
        .lastOrNull {
            it.role == Role.UNKNOWN &&
                !it.isPasswordInput &&
                !poisonRe.containsMatchIn(it.corpus)
        }
        ?.promote(Role.USERNAME)
}
```

This encodes the layout fact that login usernames sit immediately above their password, and
it is what rescues fully anonymous screens (e.g. fields named `field1`/`field2`) — the
password's `inputType` anchors the topology, and position does the rest. The poison veto
still applies, so a search box above a lone password is *not* promoted.

**Override discipline.** `promote()` refuses to touch Tier 1 fields. Developer declarations
always win over our inference; topology only reassigns Tier 2 guesses.

## 6. Confidence as an exported contract

Every classification carries `(tier, confidence)`: 1.0 / 0.6 / 0.8 for Tiers 1/2/3. The
classifier deliberately does **not** decide policy — whether 0.6 is enough to auto-offer or
only to suggest is a product decision made above the engine, exactly as in the browser
architecture (its Section 2). For the research prototype, the provider offers the locked
entry for any classified field and logs `(role, tier, confidence)` per screen, giving the
experiment log per-tier accuracy data for free.

## 7. Why not a per-app rule engine

The tempting alternative — `if (packageName == "com.instagram.android") { ... }` — is the
Android translation of the browser world's `if (domain === ...)` antipattern, and it is
rejected for the same reasons plus one Android-specific one:

1. **Unbounded maintenance.** Rules grow with the app catalog and rot with every app update.
   The 3-tier engine is O(1) in the number of supported apps by construction.
2. **Language independence.** Text-first rules break outside English. Our primary signals —
   `autofillHints`, `autocomplete`, `inputType`, password topology — carry zero localized
   text. (Tier 2's English lexicon is an acknowledged *assist*, not the foundation;
   Section 9.)
3. **Precision without enumeration.** The poison veto handles the dangerous
   false-positive class globally; per-app rules would re-solve it one app at a time.
4. **Android-specific:** package-targeted rules invite *package-spoofing phishing* — a
   malicious app naming fields to impersonate another app's layout is indistinguishable at
   the structure level. Origin binding (which credential to offer for which package) is a
   separate layer above classification, as it is in the browser design, and per-app fill
   rules would blur that security boundary.

## 8. Expected classification of the lab fixtures (predictions to check in Phase 3)

| Fixture (hints OFF) | Expected per-field result | Expected intent |
| --- | --- | --- |
| Login | `loginUsername` → USERNAME (T2: "user"), `loginPassword` → PASSWORD_CURRENT (T2: inputType) | LOGIN |
| Registration | `regEmail` → EMAIL (T2), both passwords → PASSWORD_NEW (T3 rule: 2 pwds, no marker) | REGISTRATION |
| Password change | `pcCurrentPassword` → PASSWORD_CURRENT (T2 marker), other two → PASSWORD_NEW (T3) | PASSWORD_CHANGE |
| Split step 1 | `splitUsername` → USERNAME (T2) | LOGIN (identifier-only row) |
| Split step 2 | `splitPassword` → PASSWORD_CURRENT (T2) | LOGIN |
| WebView, form 1 | Both fields → T1 via `autocomplete` | LOGIN |
| WebView, form 2 | `usr` → USERNAME (T2: "usr"→`user`), `pwd` → PASSWORD_CURRENT (html `type=password`) | LOGIN |
| Custom view | virtual username → USERNAME (T2: hint "Username"), virtual password → PASSWORD_CURRENT (T2: inputType) | LOGIN |
| No-autofill screen | no candidates at all (subtree pruned by the platform) | UNKNOWN / no fill response |
| Compose (Arm A) | **no candidates** (Compose 1.7 reports nothing) | no fill response |
| Compose (Arm B) | both → T1 via `AutofillType` → hints | LOGIN |

Any mismatch between this table and EXP logs is, by definition, a finding.

## 9. Known gaps and falsification criteria

| # | Gap / assumption | What would falsify or force revision | Planned probe |
| --- | --- | --- | --- |
| 1 | Tier 2 lexicon is English-only (`user`, `e.?mail`, ...) | A localized fixture (e.g. Turkish "Kullanıcı adı"/"Şifre" hints, generic ids) failing to classify | Localized fixture variant, Phase 3 |
| 2 | Positional fallback assumes username-above-password | Real apps with password-first layouts or interleaved decorative fields misclassified | Real-app survey (topic 10) |
| 3 | `inputType` password variations are reliably set | Real apps using plain `text` + manual masking would break the topology anchor | Real-app survey |
| 4 | Poison list completeness | Any observed credential-into-non-credential fill in EXP runs | All EXP runs |
| 5 | 2-passwords-no-marker ⇒ REGISTRATION | Login forms with password+PIN pairs (banking) would be misread as registration | Real-app survey; may need a PIN role |
| 6 | Compose invisibility (1.7) | A Compose BOM bump making default fields visible flips topic-10 conclusions | Re-run EXP-003 after BOM bump |
| 7 | OTP-vs-password ambiguity on single-field screens | Numeric OTP fields with `numberPassword` inputType classify as PASSWORD_CURRENT | OTP fixture variant |

## 10. Code map

| Concern | Location |
| --- | --- |
| Candidate selection | `FieldClassifier.collect()` |
| Tier 1 maps | `tier1HintMap`, `tier1AutocompleteMap` |
| Tier 2 corpus + regexes | `corpusOf()`, `poisonRe`, `otpRe`, `emailRe`, `userRe`, `currentPwdRe`, `newPwdRe` |
| Password structural detection | `isPasswordInput()` |
| Tier 3 topology + positional fallback | `applyTopology()`, `promote()` |
| Log evidence format | `Result.describe()` → `adb logcat -s UpSpaAutofill` |
