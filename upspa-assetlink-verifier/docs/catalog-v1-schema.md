# UpSPA Standalone Identity Catalog Schema & Validation Specification (v1)

## 1. Purpose and Scope
This document specifies the format, security invariants, validation behavior, and consumer contract for the standalone UpSPA Identity Catalog (Schema Version 1). 
The catalog stores credential derivation metadata, password policies, encoder counters, and Digital Asset Links alias evidence for enrolled accounts.

**Scope:**
- This specification and its implementation in `org.upspa.catalog` are purely standalone Kotlin/JVM components.
- In-memory strict validation and invariant enforcement.
- Out of scope: Direct Android SDK integration, JNI/FFI native bindings, persistent disk encryption implementations, and legacy identity migration pipelines.

---

## 2. Three-Concept Identity Separation
To prevent domain-spoofing, cross-origin impersonation, and confused-deputy attacks, UpSPA separates identity into three distinct security concepts:

1. **Requested Identity (At Request Time):** The ephemeral web origin or application identifier requested by an interactive caller or autofill prompt.
2. **Verified Service (Cryptographic Fact):** The authoritative relationship established between a web origin and an Android package, proven at request time by Digital Asset Links verification.
3. **Exact Enrolled Origin (Stored Authority):** The exact origin recorded at account enrollment against which credentials were originally derived.

**Storage Boundaries:**
Only the **Exact Enrolled Origin** (`enrolledOrigin`) and the **Alias Evidence Reference** (`aliasEvidence.reference`) are persisted in the catalog. The ephemeral requested identity and runtime verification proofs are never stored in the catalog.

---

## 3. Catalog Field Specification & Sensitivity Classification

### Data Sensitivity Classes
- **S0 (Transient Secret):** Master password, secret seed/key, derived passwords, `secretB64`. **Never stored in the catalog.**
- **S1 (User Identifier):** User account reference (`accountReference`). Must be encrypted at rest; never output in diagnostics/logs.
- **S2 (Site-Linkage Metadata):** Exact enrolled web origin (`enrolledOrigin`, alias `sourceOrigin`). Must be encrypted at rest; never output in diagnostics/logs.
- **S3 (Configuration Metadata):** Password policy (`policy`), encoder counter (`encoderCounter`). Persisted; may appear in diagnostics if sanitized.
- **S4 (Public / Version Metadata):** Schema versions (`schemaVersion`, `compatibilityProfileVersion`), Android package name (`packageName`), certificate digest (`signingCertificateDigest`). Publicly auditable.

### Field Table

| Field Path | Type | Required | Validation Invariant | Sensitivity | Lifetime / Persistence | Allowed in Logs |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `schemaVersion` | `Int` | Yes | Must equal `1` | S4 | Static / Catalog Lifecycle | Yes |
| `entries` | `List<CatalogEntry>` | Yes | Array of entries; no duplicate `(enrolledOrigin, accountReference)` pairs | Composite | Account Lifecycle | No (Aggregate count only) |
| `entries[].enrolledOrigin` | `String` (Origin) | Yes | Canonical WHATWG origin (`Origin.parse(s).toOriginString() == s`); exact scheme, lowercase host, port | S2 | Enrolled Account Lifetime | **No** (Redacted) |
| `entries[].accountReference` | `String` | Yes | Non-empty, no edge whitespace, no `\|`, no control chars, no unpaired surrogates, max 256 UTF-16 chars | S1 | Enrolled Account Lifetime | **No** (Redacted) |
| `entries[].compatibilityProfileVersion` | `Int` | Yes | Must equal `1` | S4 | Enrolled Account Lifetime | Yes |
| `entries[].encoderCounter` | `Long` | Yes | Range `0..4294967295` (Rust `u32`) | S3 | Monotonic / Dynamic | Yes |
| `entries[].policy` | `Object` | Yes | Fully normalized 9-field policy; meets 7 cross-language invariants | S3 | Enrolled Account Lifetime | Yes (Structured form) |
| `entries[].policy.minLen` | `Int` | Yes | `minLen >= 8` and `minLen <= maxLen`; must fit 32-bit signed integer (`LENGTH_OUT_OF_RANGE`) | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.maxLen` | `Int` | Yes | If `maxLen > 64` then `maxLen == minLen`; must fit 32-bit signed integer (`LENGTH_OUT_OF_RANGE`) | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.requireUpper` | `Boolean` | Yes | Strict boolean | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.requireLower` | `Boolean` | Yes | Strict boolean | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.requireDigit` | `Boolean` | Yes | Strict boolean | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.requireSymbol` | `Boolean` | Yes | Strict boolean | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.allowedSymbols` | `String` | Yes | No duplicate code points; non-empty (for both `requireSymbol=true` [`EMPTY_SYMBOLS_WITH_REQUIRE_SYMBOL`] and `requireSymbol=false` [`EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL`] for browser/Rust parity); no whitespace if `forbidWhitespace` | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.forbidWhitespace` | `Boolean` | Yes | Strict boolean | S3 | Enrolled Account Lifetime | Yes |
| `entries[].policy.forbiddenSubstrings` | `List<String>` | Yes | Every item non-empty and equals `trim().lowercase()` | S3 | Enrolled Account Lifetime | Yes |
| `entries[].aliasEvidence` | `Object` | Yes | Valid status and cross-field evidence reference/reason | S4 / S2 | Dynamic / Refreshable | No (Reference contains S2) |
| `entries[].aliasEvidence.status` | `Enum` | Yes | One of `VERIFIED`, `UNVERIFIED`, `PENDING`, `MANUAL` | S4 | Dynamic / Refreshable | Yes (Status enum only) |
| `entries[].aliasEvidence.reference` | `Object?` | Conditional | Required for `VERIFIED`; forbidden for `MANUAL`; optional for `UNVERIFIED`/`PENDING` | S4 / S2 | Dynamic / Refreshable | No |
| `entries[].aliasEvidence.reference.packageName` | `String` | Conditional | Valid Android package name identifier | S4 | Dynamic / Refreshable | Yes |
| `entries[].aliasEvidence.reference.signingCertificateDigest` | `String` | Conditional | Normalized 32-byte uppercase colon-separated SHA-256 hex | S4 | Dynamic / Refreshable | Yes |
| `entries[].aliasEvidence.reference.sourceOrigin` | `String` | Conditional | Must EXACTLY match entry `enrolledOrigin` (scheme, host, port) | S2 | Dynamic / Refreshable | **No** (Redacted) |
| `entries[].aliasEvidence.unverifiedReason` | `Enum?` | Conditional | Required for `UNVERIFIED`; forbidden for `VERIFIED`, `PENDING`, `MANUAL` | S4 | Dynamic / Refreshable | Yes |

---

## 4. Identity Derivation & Injectivity Guarantees

### Derivation Formula
The login-server credential derivation identity is defined as:
```text
derivationIdentity = enrolledOrigin.toOriginString() + "|" + accountReference.value
```

### Separator and Injection Mitigation
1. **Unescaped Separator (`|`):** The separator character `|` is not escaped by the underlying cryptographic derivation primitives.
2. **Ambiguity Prevention:** If `|` were permitted in `accountReference`, an attacker could construct collision preimages. For example:
   - Origin: `https://example.com`, Account: `alice|admin`
   - Origin: `https://example.com|alice`, Account: `admin` (if origins were arbitrary strings)
   By strictly validating that `accountReference` contains **no `|` character**, and because `enrolledOrigin.toOriginString()` is a strictly validated WHATWG URI origin that cannot contain unencoded `|` characters, the mapping:
   $$\text{Map}: (\text{enrolledOrigin}, \text{accountReference}) \mapsto \text{derivationIdentity}$$
   is provably **injective**. No two distinct valid pairs can ever produce the same derivation identity.
3. **Distinct Subdomains and Ports:**
   - `https://example.com|alice`
   - `https://www.example.com|alice`
   - `https://example.com:8443|alice`
   Are pairwise distinct derivation inputs and generate isolated cryptographic credentials.
4. **Legacy Migration Notice:** Legacy accounts whose usernames contain `|` are incompatible with this invariant and require an explicit migration/encoding decision outside the scope of this schema.

---

## 5. Versioning Policy
- `CatalogSchemaVersion` supported set is exactly `{1}`.
- `CompatibilityProfileVersion` supported set is exactly `{1}`.
- Unsupported versions (e.g. `0`, `2`, `"1"`) are rejected immediately with typed rejections (`UnsupportedSchemaVersion`, `UnsupportedCompatibilityProfileVersion`).
- The validator never performs best-effort fallback, downgrade, or lenient parsing.

---

## 6. Validation Execution Order & Typed Rejections
Validation executes strictly fail-closed and deterministic. The validator halts and returns the FIRST detected violation.

### Order of Checks:
0. **Surrogate Well-Formedness:** Pre-scan raw string before UTF-8 conversion and inspect unescaped strings/keys to reject unpaired surrogates (`UnpairedSurrogate`). Adheres strictly to rejection hygiene: zero echoed content.
1. **Input Size Limit:** Max 65,536 bytes (64 KiB) UTF-8 (`InputTooLarge`).
2. **JSON Syntax & Nesting:** Strict JSON grammar, max depth 8 (`MalformedJson`, `NestingTooDeep`). Enforces strict RFC 8259 Section 7 string grammar: pre-scans UTF-8 bytes to reject any unescaped code points below `0x20` inside quoted strings (including literal newlines and tabs), while escaped controls (`\n`, `\t`) and inter-token whitespace remain valid.
3. **No Trailing Data:** Zero unconsumed non-whitespace tokens after top-level object (`TrailingData`).
4. **Key Uniqueness & Strictness:** No duplicate keys (`DuplicateJsonKey`), no unknown keys (`UnknownField`).
5. **Document Structure:** `schemaVersion` present, integer, supported (`MissingField`, `WrongType`, `UnsupportedSchemaVersion`).
6. **Entries Array:** `entries` present and is an array (`MissingField`, `WrongType`).
7. **Per-Entry Evaluation (in array index order $0 \dots N-1$):**
   a. **Enrolled Origin:** Missing/null -> `MissingEnrolledOrigin(index)`. Valid parse -> `InvalidEnrolledOrigin(index)`. Exact canonical check -> `NonCanonicalEnrolledOrigin(index)`.
   b. **Account Reference:** Missing/wrong type -> `MissingField`/`WrongType`. Invariant checks -> `InvalidAccountReference(index, code)`.
   c. **Compatibility Profile Version:** Present, integer, supported -> `UnsupportedCompatibilityProfileVersion(index, found)`.
   d. **Encoder Counter:** Present, integer lexeme, $0 \le c \le 2^{32}-1$ -> `InvalidEncoderCounter(index)`.
   e. **Normalized Password Policy:** All 9 fields mandatory -> `PartialPolicy(index)`. Invariants -> `MalformedPolicy(index, code)` (including `EMPTY_SYMBOLS_WITH_REQUIRE_SYMBOL` and `EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL`).
   f. **Alias Evidence:** Syntax, status, cross-field rules -> `MalformedAliasEvidence(index, code)`.
   g. **Entry-Level Binding:**
      - If `reference != null`, `reference.sourceOrigin == enrolledOrigin` -> `AliasOriginMismatch(index)`.
      - If `status == VERIFIED`, `enrolledOrigin.isHttps` -> `AliasRequiresHttps(index)`.
8. **Document Uniqueness:** Pair `(enrolledOrigin, accountReference)` must be unique across all entries -> `DuplicateEntry(firstIndex, secondIndex)`.
9. **Unhandled Exception Guard:** Any internal unexpected error is converted to `InternalValidationError`.

---

## 7. Explicit Assumptions and Open Decisions
1. **Account Reference Max Length:** Capped at 256 UTF-16 code units (assumption documented for parity).
2. **Nesting Depth:** Capped at 8 (sufficient for schema v1, prevents recursion attacks).
3. **Input Size Limit:** Capped at 64 KiB (sufficient for large catalogs while preventing DoS).
4. **No Lenient Policy Normalization:** Raw or incomplete policies are rejected; clients must run policy normalizers prior to catalog emission.
5. **PSL / IDNA Handling:** `Origin` accepts only ASCII host characters, so non-ASCII (Unicode) hosts and trailing-dot hosts are rejected, while punycode (`xn--...`) hosts are plain ASCII and are accepted as opaque literal strings, without IDNA/UTS #46 validation and without any Public Suffix List logic. Full IDNA and PSL handling is deferred as a future roadmap item (`ID-01..04`).

---

## 8. Known Limitations
- **Format vs Deployment:** A validated catalog certifies format, schema, and internal consistency only.
- **Claimed Evidence:** `aliasEvidence.status == VERIFIED` in a catalog records a historical claim; it is necessary but NOT sufficient for runtime credential release.
- **Pure Verifier Isolation:** Pure verification with injected evidence verifies cryptographic consistency but does not guarantee live server deployment.

---

## 9. Consumer Contract (Proposal)
In accordance with `CatalogConsumerContract.kt`:
1. **Single Gatekeeper:** Consumers must parse catalogs exclusively via `StrictCatalogValidator`.
2. **Exact Candidate Selection:** Candidate entries for a verified service must match via exact `EnrolledOrigin` equality (`targetOrigin == entry.enrolledOrigin`). Subdomain matching, suffix matching, or package-only matching is forbidden.
3. **Runtime Release Dual-Requirement:** Credentials may be released to an app if and only if:
   a. `entry.aliasEvidence.isEligibleForCredentialRelease == true` (`status == VERIFIED`), **AND**
   b. A fresh Digital Asset Links verification at request time returns `VerificationResult.Verified` whose `origin`, `packageName`, and `matchedFingerprint` exactly match `entry.aliasEvidence.reference`.
4. **Non-Releasing Statuses:** `MANUAL`, `PENDING`, and `UNVERIFIED` entries must never release credentials to an external app.
5. **Redaction Hygiene:** Consumers must never log `accountReference` or `enrolledOrigin`. Opaque local IDs must be used in diagnostics.
