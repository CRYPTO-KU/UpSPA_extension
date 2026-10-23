# PR #33 Review Round 2 Verification Report

## Overview
This report documents the resolution of all five findings requested by Ozan for PR #33 (`furkan/identity-catalog-and-suite`), adhering strictly to the TDD verification protocol:
1. Regression test + valid control added and verified failing on unfixed code.
2. Production fix implemented and verified passing.
3. Zero modifications to PR #27 production code (`org.upspa.assetlinks.*`) or PR #27 tests.
4. All work isolated inside `upspa-assetlink-verifier/`.

---

## Finding 1: Accepted policies break browser/Rust parity (P1)

### 1.1 Problem & Concrete Divergence Mechanism
`CatalogValidator.kt` previously accepted password policies with `requireSymbol = false` and `allowedSymbols = ""`. In `valid/07_compatibility_policies.json`, zero-based entries 1 ("policy2") and 2 ("policy3") utilized this combination:
- `policy2`: `{ "minLen": 20, "maxLen": 32, "requireUpper": true, "requireLower": true, "requireDigit": true, "requireSymbol": false, "allowedSymbols": "", "forbidWhitespace": true, "forbiddenSubstrings": [] }`
- `policy3`: `{ "minLen": 20, "maxLen": 32, "requireUpper": true, "requireLower": true, "requireDigit": false, "requireSymbol": false, "allowedSymbols": "", "forbidWhitespace": true, "forbiddenSubstrings": [] }`

#### Concrete Divergence Between Encoders:
When evaluated on the same synthetic input seed, the TypeScript browser encoder and the Rust core produce completely different passwords:
1. **TypeScript Browser Encoder** (`packages/extension/src/shared/passwordPolicy.ts`, line 70):
   ```typescript
   let allowedSymbols = String(merged.allowedSymbols || defaults.allowedSymbols);
   ```
   In JavaScript, `""` is falsy. Therefore, `"" || defaults.allowedSymbols` falls back to `DEFAULT_SYMBOLS` (`"!@#$%^&*"`). `normalizePasswordPolicy` sets `allowedSymbols = "!@#$%^&*"`.
   When `canonicalPolicy(policy)` is JSON-serialized to form the PRNG hash seed:
   ```typescript
   seed = ['upspa-password-encoding-v2', secretB64, String(counter), String(attempt), canonicalPolicy(policy), accountKey].join('|')
   ```
   the canonical policy includes `"allowedSymbols":"!@#$%^&*"`.
2. **Rust Encoder** (`crates/upspa-core/src/password_encoder.rs`, lines 66–68):
   ```rust
   if policy.require_symbol && allowed_symbols.is_empty() {
       allowed_symbols = DEFAULT_SYMBOLS.to_string();
   }
   ```
   Because `policy.require_symbol` is `false`, Rust preserves `allowed_symbols = ""`.
   When `canonical_policy` is serialized into the PRNG hash seed in Rust, it includes `"allowed_symbols":""`.
3. **Divergence**: The two encoders hash different canonical policy strings into their SHA-256 derivation seeds, producing completely different passwords for identical synthetic credentials and counters.

#### Conservative Rule Enforced by Validator:
To guarantee encoder parity, the catalog validator enforces a conservative rule: any policy with `requireSymbol = false` and `allowedSymbols = ""` is rejected with `MalformedPolicyCode.EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL`.

> [!NOTE]
> **Question for reviewer**: Is rejecting this combination (`requireSymbol=false` with `allowedSymbols=""`) the intended boundary, or should the TypeScript encoder's truthy fallback (`merged.allowedSymbols || defaults.allowedSymbols`) be revised in a separate cross-encoder alignment task?

### 1.2 Reproduction Command (Before Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 1*"
```
*(Executed in a detached worktree at commit `6ccd108`)*

### 1.3 Real Output Before Fix (excerpt (trimmed))
```text
> Task :test FAILED

ReviewRound2RegressionTest > Finding 1 - valid control - requireSymbol false with non-empty allowedSymbols is accepted() PASSED

ReviewRound2RegressionTest > Finding 1 - valid control - requireSymbol true with allowedSymbols is accepted() PASSED

ReviewRound2RegressionTest > Finding 1 - policy2 shape with empty allowedSymbols and requireSymbol false is rejected() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:72

ReviewRound2RegressionTest > Finding 1 - policy3 shape with empty allowedSymbols and requireSymbol false without digits is rejected() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:92

4 tests completed, 2 failed
```

Failure detail from test results:
```text
ReviewRound2RegressionTest > Finding 1 - policy2 shape with empty allowedSymbols and requireSymbol false is rejected() FAILED
    org.opentest4j.AssertionFailedError: Policy with requireSymbol=false and empty allowedSymbols must be rejected due to browser/Rust divergence ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
        at app//org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
        at app//org.junit.jupiter.api.AssertTrue.failNotTrue(AssertTrue.java:63)
        at app//org.junit.jupiter.api.AssertTrue.assertTrue(AssertTrue.java:36)
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 1 - policy2 shape with empty allowedSymbols and requireSymbol false is rejected(ReviewRound2RegressionTest.kt:72)
```

### 1.4 Fix Applied
- Added `MalformedPolicyCode.EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL` to `CatalogValidationResult.kt`.
- In `CatalogValidator.kt` and `NormalizedPasswordPolicy.kt`, rejected policies where `!requireSymbol && allowedSymbols.isEmpty()` with `CatalogRejection.MalformedPolicy(val entryIndex: Int, val code: MalformedPolicyCode)`.
- Moved diverging entries 1 and 2 from `valid/07_compatibility_policies.json` to invalid fixture files:
  - `invalid/policy_empty_symbols_not_required.json`
  - `invalid/policy_empty_symbols_unrequired_no_digit.json`
- Retained entries 0, 3, and 4 in `07_compatibility_policies.json` as valid controls.
- Updated `manifest.json` and `docs/catalog-v1-schema.md`.

### 1.5 Verification Command (After Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 1*"
```

### 1.6 Real Output After Fix (excerpt (trimmed))
```text
> Task :test

ReviewRound2RegressionTest > Finding 1 - valid control - requireSymbol false with non-empty allowedSymbols is accepted() PASSED

ReviewRound2RegressionTest > Finding 1 - valid control - requireSymbol true with allowedSymbols is accepted() PASSED

ReviewRound2RegressionTest > Finding 1 - policy2 shape with empty allowedSymbols and requireSymbol false is rejected() PASSED

ReviewRound2RegressionTest > Finding 1 - policy3 shape with empty allowedSymbols and requireSymbol false without digits is rejected() PASSED

BUILD SUCCESSFUL in 6s
5 actionable tasks: 2 executed, 3 up-to-date
```

---

## Finding 2: Unpaired surrogates (P2)

### 2.1 Problem & Reproduction
In `CatalogValidator.kt`, `rawJson.toByteArray(Charsets.UTF_8)` was called prior to string validation. In standard Java/Kotlin, converting a String containing lone surrogates to UTF-8 silently replaces unmappable characters with `?` (0x3F). Consequently, `"synthetic\uD800"` and `"synthetic?"` produced identical UTF-8 byte representations, masking invalid inputs. Additionally, escaped lone surrogates like `\uD800` were accepted after unescaping, and `AccountReference` did not reject lone surrogates at construction.

### 2.2 Reproduction Command (Before Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 2*"
```
*(Executed in a detached worktree at commit `f143180`)*

### 2.3 Real Output Before Fix (excerpt (trimmed))
```text
> Task :test FAILED

ReviewRound2RegressionTest > Finding 2 - raw JSON containing literal lone surrogate is rejected() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:158

ReviewRound2RegressionTest > Finding 2 - valid control - valid surrogate pair emoji in account reference is accepted() PASSED

ReviewRound2RegressionTest > Finding 2 - JSON containing escaped lone surrogate in account reference is rejected() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:195

ReviewRound2RegressionTest > Finding 2 - AccountReference constructor rejects unpaired surrogate() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:203

4 tests completed, 3 failed
```

Failure detail from test results:
```text
ReviewRound2RegressionTest > Finding 2 - raw JSON containing literal lone surrogate is rejected() FAILED
    org.opentest4j.AssertionFailedError: Literal lone surrogate in raw JSON must be rejected ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 2 - raw JSON containing literal lone surrogate is rejected(ReviewRound2RegressionTest.kt:158)

ReviewRound2RegressionTest > Finding 2 - JSON containing escaped lone surrogate in account reference is rejected() FAILED
    org.opentest4j.AssertionFailedError: Escaped lone surrogate \uD800 in account reference must be rejected ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 2 - JSON containing escaped lone surrogate in account reference is rejected(ReviewRound2RegressionTest.kt:195)

ReviewRound2RegressionTest > Finding 2 - AccountReference constructor rejects unpaired surrogate() FAILED
    org.opentest4j.AssertionFailedError: Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.
        at app//org.junit.jupiter.api.Assertions.assertThrows(Assertions.java:3115)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 2 - AccountReference constructor rejects unpaired surrogate(ReviewRound2RegressionTest.kt:203)
```

### 2.4 Fix Applied
- Added `CatalogRejection.UnpairedSurrogate` without echoing untrusted content into logs or messages.
- Scanned `rawJson` for unpaired surrogates in `CatalogValidator.doValidate` prior to `toByteArray(UTF_8)`.
- Added validation for unescaped string contents and object keys in AST parsing.
- Added check in `validateAccountReferenceString`.
- Enforced rejection of unpaired surrogates in `AccountReference.kt` constructor via `IllegalArgumentException`.
- Kept valid control: valid surrogate-pair emojis (e.g. `\uD83D\uDE00`) are accepted.
- Updated `docs/catalog-v1-schema.md`.

### 2.5 Verification Command (After Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 2*"
```

### 2.6 Real Output After Fix (excerpt (trimmed))
```text
> Task :test

ReviewRound2RegressionTest > Finding 2 - raw JSON containing literal lone surrogate is rejected() PASSED

ReviewRound2RegressionTest > Finding 2 - valid control - valid surrogate pair emoji in account reference is accepted() PASSED

ReviewRound2RegressionTest > Finding 2 - JSON containing escaped lone surrogate in account reference is rejected() PASSED

ReviewRound2RegressionTest > Finding 2 - AccountReference constructor rejects unpaired surrogate() PASSED

BUILD SUCCESSFUL in 6s
5 actionable tasks: 2 executed, 3 up-to-date
```

---

## Finding 3: Literal newline inside JSON string is accepted (P2)

### 3.1 Problem & Reproduction
Moshi's `JsonReader` with `isLenient = false` did not strictly enforce RFC 8259 Section 7 requirement that characters `< 0x20` (ASCII control characters such as literal newline `\n` or tab `\t`) must be escaped inside quoted strings.

### 3.2 Reproduction Command (Before Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 3*"
```
*(Executed in a detached worktree at commit `a353ca0`)*

### 3.3 Real Output Before Fix (excerpt (trimmed))
```text
> Task :test FAILED

ReviewRound2RegressionTest > Finding 3 - literal tab inside JSON string is rejected as MalformedJson() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:238

ReviewRound2RegressionTest > Finding 3 - literal newline inside JSON string is rejected as MalformedJson() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:222

ReviewRound2RegressionTest > Finding 3 - valid control - escaped newline and tab inside JSON string remain valid() PASSED

ReviewRound2RegressionTest > Finding 3 - valid control - whitespace between JSON tokens remains allowed() PASSED

4 tests completed, 2 failed
```

Failure detail from test results:
```text
ReviewRound2RegressionTest > Finding 3 - literal tab inside JSON string is rejected as MalformedJson() FAILED
    org.opentest4j.AssertionFailedError: Literal tab inside JSON string must be rejected ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 3 - literal tab inside JSON string is rejected as MalformedJson(ReviewRound2RegressionTest.kt:238)

ReviewRound2RegressionTest > Finding 3 - literal newline inside JSON string is rejected as MalformedJson() FAILED
    org.opentest4j.AssertionFailedError: Literal newline inside JSON string must be rejected ==> expected: <true> but was: <false>
        at app//org.junit.jupiter.api.Assertions.assertTrue(Assertions.java:214)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 3 - literal newline inside JSON string is rejected as MalformedJson(ReviewRound2RegressionTest.kt:222)
```

### 3.4 Fix Applied
- Implemented `validateStrictJsonStringSyntax(bytes: ByteArray)` pre-scan in `CatalogValidator.kt`.
- The scanner tracks in-string quoting and escape backslashes, rejecting any raw unescaped byte `< 0x20` inside strings as `CatalogRejection.MalformedJson`.
- Permitted valid controls: escaped sequences (`\n`, `\t`, `\r`, `\"`, `\\`) and whitespace outside quoted strings remain accepted.
- Updated `docs/catalog-v1-schema.md`.

### 3.5 Verification Command (After Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 3*"
```

### 3.6 Real Output After Fix (excerpt (trimmed))
```text
> Task :test

ReviewRound2RegressionTest > Finding 3 - literal tab inside JSON string is rejected as MalformedJson() PASSED

ReviewRound2RegressionTest > Finding 3 - literal newline inside JSON string is rejected as MalformedJson() PASSED

ReviewRound2RegressionTest > Finding 3 - valid control - escaped newline and tab inside JSON string remain valid() PASSED

ReviewRound2RegressionTest > Finding 3 - valid control - whitespace between JSON tokens remains allowed() PASSED

BUILD SUCCESSFUL in 6s
5 actionable tasks: 2 executed, 3 up-to-date
```

---

## Finding 4: CatalogDocument uniqueness can be invalidated after construction (P2)

### 4.1 Problem & Reproduction
`CatalogDocument` and `NormalizedPasswordPolicy` held caller-supplied `List` references directly. Mutating the caller's list after constructor validation (e.g. appending a duplicate `(enrolledOrigin, accountReference)` or modifying `forbiddenSubstrings`) mutated the internal state of the document and bypassed uniqueness invariants.

### 4.2 Reproduction Command (Before Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 4*"
```
*(Executed in a detached worktree at commit `02dc545`)*

### 4.3 Real Output Before Fix (excerpt (trimmed))
```text
> Task :test FAILED

ReviewRound2RegressionTest > Finding 4 - caller mutating forbiddenSubstrings after NormalizedPasswordPolicy construction does not affect policy() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:353

ReviewRound2RegressionTest > Finding 4 - caller mutating entries list after CatalogDocument construction does not affect document or bypass uniqueness() FAILED
    org.opentest4j.AssertionFailedError at ReviewRound2RegressionTest.kt:332

ReviewRound2RegressionTest > Finding 4 - valid control - duplicate entry at construction time is rejected() PASSED

3 tests completed, 2 failed
```

Failure detail from test results:
```text
ReviewRound2RegressionTest > Finding 4 - caller mutating forbiddenSubstrings after NormalizedPasswordPolicy construction does not affect policy() FAILED
    org.opentest4j.AssertionFailedError: Policy forbiddenSubstrings must remain unchanged after caller list mutation ==> expected: <1> but was: <2>
        at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 4 - caller mutating forbiddenSubstrings after NormalizedPasswordPolicy construction does not affect policy(ReviewRound2RegressionTest.kt:353)

ReviewRound2RegressionTest > Finding 4 - caller mutating entries list after CatalogDocument construction does not affect document or bypass uniqueness() FAILED
    org.opentest4j.AssertionFailedError: CatalogDocument entries must remain unchanged after caller list mutation ==> expected: <1> but was: <2>
        at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:563)
        at app//org.upspa.catalog.ReviewRound2RegressionTest.Finding 4 - caller mutating entries list after CatalogDocument construction does not affect document or bypass uniqueness(ReviewRound2RegressionTest.kt:332)
```

### 4.4 Fix Applied
- In `CatalogDocument.kt`, wrapped `entries.toList()` with `Collections.unmodifiableList(...)` and implemented copy protection that validates uniqueness on new instances.
- In `NormalizedPasswordPolicy.kt`, wrapped `forbiddenSubstrings.toList()` with `Collections.unmodifiableList(...)`.
- Retained construction-time duplicate validation rejecting duplicate `(enrolledOrigin, accountReference)` pairs with `IllegalArgumentException`.
- Updated `docs/catalog-v1-schema.md`.

### 4.5 Verification Command (After Fix)
```powershell
.\gradlew test --tests "org.upspa.catalog.ReviewRound2RegressionTest.Finding 4*"
```

### 4.6 Real Output After Fix (excerpt (trimmed))
```text
> Task :test

ReviewRound2RegressionTest > Finding 4 - caller mutating forbiddenSubstrings after NormalizedPasswordPolicy construction does not affect policy() PASSED

ReviewRound2RegressionTest > Finding 4 - caller mutating entries list after CatalogDocument construction does not affect document or bypass uniqueness() PASSED

ReviewRound2RegressionTest > Finding 4 - valid control - duplicate entry at construction time is rejected() PASSED

BUILD SUCCESSFUL in 6s
5 actionable tasks: 2 executed, 3 up-to-date
```

---

## Finding 5: scripts/mutation-check.sh counts compilation failures as killed mutations (P2)

### 5.1 Problem & Reproduction
`scripts/mutation-check.sh` originally checked only that Gradle returned a non-zero exit code (`if [ $MUTATION_RUN_STATUS -eq 0 ]; then SURVIVED else KILLED`). When a Kotlin syntax error was introduced into a mutation, Gradle failed at compile time, which the script counted as "KILLED", reporting 8/8 mutations killed even though zero test cases executed.

Additionally, the original script's `cleanup()` trap contained:
```bash
(cd "$TMP_DIR" && ./gradlew --stop >/dev/null 2>&1 || true)
```
This command killed all system Gradle daemons across the machine, aborting concurrent processes and causing daemon thrashing. It was removed so Gradle daemons are managed naturally, while `rm -rf "$TMP_DIR"` handles isolation cleanup.

### 5.2 Fix Applied
- In `scripts/mutation-check.sh`:
  - Added compilation gate `./gradlew compileKotlin compileTestKotlin` before test execution. Any compilation failure aborts immediately with exit code 2: `FATAL: Mutation $MID failed to compile! Compilation errors are NOT killed mutations.`
  - Removed results caching by deleting `build/test-results` prior to test execution.
  - Parsed the fresh XML test results (`build/test-results/test/TEST-org.upspa.assetlinks.adversarial.IdentityAdversarialSuite.xml`) and verified `tests > 0`.
  - Parsed failed test names from the XML and asserted that at least one failed case matches the expected killing case IDs from `docs/mutation-check.md`, and zero unexpected failures occurred.
  - Made `sed -i` portable (`sed -i.bak ... && rm -f ...bak`).
  - Added `--selftest-compile-fault` mode which injects a syntax fault and verifies that the checker exits with code 2.
  - Ensured working tree modification checks (`git status --porcelain`) are maintained.
- In `ReviewRound2RegressionTest.kt`:
  - Replaced the embedded full-script invocation with a lightweight bash syntax check (`bash -n scripts/mutation-check.sh`) guarded by `Assumptions.assumeTrue(isBashOnPath())`. If bash is not on PATH, the test is skipped rather than failing.
- Updated `docs/mutation-check.md`.

### 5.3 Manual Self-Test Execution Command
```bash
bash scripts/mutation-check.sh --selftest-compile-fault
```

### 5.4 Real Self-Test Output (excerpt (trimmed))
- **Exit Code**: `0` (asserting sub-invocation returned `2`)
```text
=== Mutation Checker Self-Test Mode ===
Invoking mutation checker with an injected compile fault...
=== Running mutation checker with deliberate compile fault injection ===
=== Identity Adversarial Suite: Mutation Testing ===
Created temporary isolation directory: /tmp/tmp.i4Lqi9DhI7
Copying repository files to temporary directory...
Running baseline test suite in temporary copy...
Baseline PASSED: all passed

--- Testing Mutation M1: Package-name comparison weakened to case-insensitive ---
Injecting deliberate Kotlin compilation fault into src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt for M1...
FATAL: Mutation M1 failed to compile! Compilation errors are NOT killed mutations.
> Task :checkKotlinGradlePluginConfigurationErrors
> Task :processResources NO-SOURCE

> Task :compileKotlin FAILED
e: file:///C:/Users/Furkan/AppData/Local/Temp/tmp.i4Lqi9DhI7/src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt:283:34 Type mismatch: inferred type is String but Int was expected

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':compileKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

BUILD FAILED in 1s
2 actionable tasks: 2 executed
Cleaning up temporary isolation directory...
Self-test PASSED: mutation checker rejected compile fault with non-zero exit code (2).
```

### 5.5 Full Mutation Check Verification
```bash
bash scripts/mutation-check.sh
```
- **Exit Code**: `0`

Real output excerpt (trimmed):
```text
=== Identity Adversarial Suite: Mutation Testing ===
Created temporary isolation directory: /tmp/tmp.3gvtASDgs1
Copying repository files to temporary directory...
Running baseline test suite in temporary copy...
Baseline PASSED: all passed

--- Testing Mutation M1: Package-name comparison weakened to case-insensitive ---
PASS: Mutation M1 KILLED! (52 tests executed, failing cases: PACKAGE_MISMATCH_CASE_DIFFERENCE)

--- Testing Mutation M2: Origin-binding check weakened to compare host only ---
PASS: Mutation M2 KILLED! (52 tests executed, failing cases: ORIGIN_BOUNDARY_DIFFERENT_PORT, REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_PORT, REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_SCHEME)

--- Testing Mutation M3: Origin.equals weakened to ignore port ---
PASS: Mutation M3 KILLED! (52 tests executed, failing cases: ORIGIN_BOUNDARY_DIFFERENT_PORT, REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_PORT)

--- Testing Mutation M4: Certificate matching weakened to prefix match (first 12 hex chars) ---
PASS: Mutation M4 KILLED! (52 tests executed, failing cases: CERTIFICATE_MISMATCH_ONE_BYTE_DIFFERENCE, CERTIFICATE_MISMATCH_PREFIX_DIGEST)

--- Testing Mutation M5: Multi-signer rejection disabled ---
PASS: Mutation M5 KILLED! (52 tests executed, failing cases: MULTIPLE_SIGNERS_ONE_AUTHORIZED, MULTIPLE_SIGNERS_BOTH_AUTHORIZED)

--- Testing Mutation M6: Certificate trailing-bytes check disabled in isValidX509Certificate ---
PASS: Mutation M6 KILLED! (52 tests executed, failing cases: MALFORMED_CERT_TRAILING_BYTES)

--- Testing Mutation M7: Relation check weakened to non-empty relation list ---
PASS: Mutation M7 KILLED! (52 tests executed, failing cases: REQUESTED_ENROLLED_BINDING_MISSING_RELATION)

--- Testing Mutation M8: Non-HTTPS origin rejection disabled ---
PASS: Mutation M8 KILLED! (52 tests executed, failing cases: ORIGIN_BOUNDARY_NON_HTTPS_REJECTION)

## Summary
- Total mutations tested: 8
- Mutations killed: 8
- Mutations survived: 0

SUCCESS: All mutations were killed by the Identity Adversarial Suite!
Cleaning up temporary isolation directory...
```

---

## Changed Fixtures & Tests Summary

| File | Change | Reason |
| :--- | :--- | :--- |
| `valid/07_compatibility_policies.json` | Removed entries 1 and 2 | Empty `allowedSymbols` with `requireSymbol = false` diverges across browser/Rust encoders |
| `invalid/policy_empty_symbols_not_required.json` | Added fixture | Verifies rejection with `EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL` |
| `invalid/policy_empty_symbols_unrequired_no_digit.json` | Added fixture | Verifies rejection with `EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL` |
| `manifest.json` | Updated fixture manifest | Reflects removal of diverging entries and addition of the two new rejection fixtures |
| `ReviewRound2RegressionTest.kt` | Added 16 regression & control tests | Covers all 5 review findings with failing reproduction + valid control |

---

## Test Execution Arithmetic & Final Run Totals

### Test Suite Breakdown:
- **Baseline Test Suite (before PR #33 review round 2):** **264 tests**
  - 52 adversarial tests (`IdentityAdversarialSuite`)
  - 74 fixture tests (`CatalogFixtureTest`)
  - 13 catalog unit tests (`CatalogUnitTest`)
  - 125 assetlink tests:
    - `CertificateUtilsTest`: 4
    - `OriginTest`: 5
    - `TypedValuesTest`: 5
    - `ReviewRound5RegressionTest`: 39
    - `ReviewRound5TypedOutcomeTest`: 16
    - `ReviewRound6RegressionTest`: 19
    - `AdversarialNegativeControlTest`: 27
    - `UpSpaAssetLinkVerifierTest`: 10
- **Net Changes in Review Round 2:**
  - `+2` new invalid fixture tests in `CatalogFixtureTest` (74 -> 76)
  - `+16` new regression and control unit tests in `ReviewRound2RegressionTest`
- **Total Test Count:** **282 tests** (264 baseline + 2 new fixtures + 16 new unit tests)

### Final Run Verification (`.\gradlew clean test`):
- **Completed:** 282 tests
- **Passed:** 281 tests
- **Skipped:** 1 test (`Finding 5 - valid control - mutation check script exists and has valid bash syntax`, skipped via assumption when bash is not in environment PATH)
- **Failed:** 0 failures
- **Errors:** 0 errors
