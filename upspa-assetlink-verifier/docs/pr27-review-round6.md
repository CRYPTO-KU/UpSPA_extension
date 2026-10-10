# PR #27 Review Round 6 Fixes — Verification & Reproduction Report

This report documents the resolution of the two issues requested by reviewer Ozan for PR #27:
- **ISSUE P1**: Signing evidence remains mutable after validation in `AppSigningInfo.kt`.
- **ISSUE P2**: Numeric relation elements bypass JSON type validation in `PureAssetLinkVerifier.kt`.

All tests and security gates below were executed locally on Windows (PowerShell) on branch `furkan/android-identity-verifier`.

---

## How to reproduce

| Step | Commit | What it contains |
|---|---|---|
| Unfixed baseline | `01a2414` | previous head of this PR (round 5 head) |
| Tests only | `3ba3d19` | adds `ReviewRound6RegressionTest.kt`, production code unchanged |
| Fixes | `eb7ab0a` | the production fixes in `AppSigningInfo.kt` and `PureAssetLinkVerifier.kt` |

To observe the failures before fix: check out `3ba3d19` and run:
```powershell
.\gradlew test --tests '*ReviewRound6RegressionTest*'
```
To observe the fixed state: check out `eb7ab0a` (or current branch head) and run:
```powershell
.\gradlew clean test
```

---

## Summary

| Issue | Failing tests before fix | Tests passing after fix |
|---|---|---|
| P1. Signing evidence immutability & defensive copying | 8 | 8 of 8 |
| P2. JSON token type strictness during decoding | 5 | 5 of 5 |
| Controls (unmutated history, valid relations, non-string tokens) | 0 (all 6 passed) | 6 of 6 |
| **Total (ReviewRound6RegressionTest)** | **13 of 19** | **19 of 19** |

---

## Issue P1: Signing evidence remains mutable after validation

### Problem
`AppSigningInfo` was a `data class` whose constructor properties retained caller-owned collection references and shared `ByteArray` instances. Validation in the `init` block only enforced invariants at construction time. Subsequent caller mutations to:
1. `rotationHistory` (e.g. removing the current signer B) allowed inconsistent history evidence to verify or caused statements matching current signers to fail.
2. `rawRotationHistory` (appending raw strings after construction with typed values) introduced conflicting representation families after construction.
3. `signingCertificates` / `signingCertificateHistory` (`ByteArray` mutation in-place) corrupted certificate evidence after construction.
4. Exposed collection and byte array getters permitted external callers to mutate internal state.

### Fix
1. Converted `AppSigningInfo` from `data class` to a normal `final class` (without compiler-generated `copy()` or destructuring components).
2. Constructor parameters now immediately create unmodifiable snapshots (`Collections.unmodifiableList` / `Collections.unmodifiableSet`) and defensive deep copies of all byte arrays (`map { it.clone() }`).
3. Class body property initialization creates the snapshots before the `init` block runs, so `init` invariants validate the held snapshots rather than caller-owned parameters.
4. Getters for `signingCertificates` and `signingCertificateHistory` return defensive byte array clones on each call.
5. Implemented explicit `equals()`, `hashCode()`, and `toString()`.
6. Updated companion factories (`fromFingerprints`, `fromMultiSigners`, `fromRotationHistory`, `fromTypedValues`, `fromX509Certificates`) to copy inputs defensively.

### Test Commands
```powershell
.\gradlew test --tests '*ReviewRound6RegressionTest*'
```

### Output before fix (commit `3ba3d19`)
```
ReviewRound6RegressionTest > ISSUE1_scenario1_app_owns_immutable_history_snapshot FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:84

ReviewRound6RegressionTest > ISSUE1_scenario1_history_mutation_pure_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:110

ReviewRound6RegressionTest > ISSUE1_scenario1_history_mutation_upspa_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:134

ReviewRound6RegressionTest > ISSUE1_scenario2_raw_history_mutation_pure_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:157

ReviewRound6RegressionTest > ISSUE1_scenario2_raw_history_mutation_upspa_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:178

ReviewRound6RegressionTest > ISSUE1_certificate_byte_array_mutation_fails_to_corrupt_app FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:196

ReviewRound6RegressionTest > ISSUE1_certificate_history_byte_array_mutation_fails_to_corrupt_app FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:218

ReviewRound6RegressionTest > ISSUE1_exposed_getters_return_defensive_copies_and_readonly_collections FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:240

ReviewRound6RegressionTest > ISSUE1_control_unmutated_history_verifies_historical_key PASSED
```

### Output after fix (commit `eb7ab0a`)
```
ReviewRound6RegressionTest > ISSUE1_scenario1_app_owns_immutable_history_snapshot PASSED
ReviewRound6RegressionTest > ISSUE1_scenario1_history_mutation_pure_verifier PASSED
ReviewRound6RegressionTest > ISSUE1_scenario1_history_mutation_upspa_verifier PASSED
ReviewRound6RegressionTest > ISSUE1_scenario2_raw_history_mutation_pure_verifier PASSED
ReviewRound6RegressionTest > ISSUE1_scenario2_raw_history_mutation_upspa_verifier PASSED
ReviewRound6RegressionTest > ISSUE1_certificate_byte_array_mutation_fails_to_corrupt_app PASSED
ReviewRound6RegressionTest > ISSUE1_certificate_history_byte_array_mutation_fails_to_corrupt_app PASSED
ReviewRound6RegressionTest > ISSUE1_exposed_getters_return_defensive_copies_and_readonly_collections PASSED
ReviewRound6RegressionTest > ISSUE1_control_unmutated_history_verifies_historical_key PASSED
```

---

## Issue P2: Numeric relation elements bypass JSON type validation

### Problem
In `PureAssetLinkVerifier.kt`, `verifyRawJson` performed a post-parsing type check (`relation.any { it !is String }`). However, Moshi's built-in String adapter coerced numbers to strings during JSON decoding. Therefore, inputs like `"relation": ["delegate_permission/common.handle_all_urls", 123]` successfully decoded `123` into string `"123"`, bypassing the post-check and verifying statements that should fail closed with `InvalidJsonFormat`. Similarly, numeric `package_name`, numeric `namespace`, and numeric elements in `sha256_cert_fingerprints` were coerced into strings instead of being rejected during decoding.

### Fix
1. Introduced `StrictStringAdapter : JsonAdapter<String>()` in `PureAssetLinkVerifier`:
   - Inspects `reader.peek()`.
   - Allows `JsonReader.Token.NULL` (returning `reader.nextNull()`).
   - Requires `JsonReader.Token.STRING` for non-null tokens; throws `JsonDataException` for any other token type (`NUMBER`, `BOOLEAN`, `BEGIN_OBJECT`, `BEGIN_ARRAY`).
   - Reads string via `reader.nextString()`.
2. Registered `StrictStringAdapter` on `Moshi.Builder().add(String::class.java, StrictStringAdapter)`.
3. Moshi now rejects non-string tokens across all string fields (`relation`, `sha256_cert_fingerprints`, `namespace`, `package_name`, `site`, `include`) during parsing, immediately producing typed rejection `VerificationResult.Rejected.InvalidJsonFormat`.

### Test Commands
```powershell
.\gradlew test --tests '*ReviewRound6RegressionTest*'
```

### Output before fix (commit `3ba3d19`)
```
ReviewRound6RegressionTest > ISSUE2_numeric_relation_element_rejected_on_pure_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:308

ReviewRound6RegressionTest > ISSUE2_numeric_relation_element_rejected_on_upspa_verifier FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:319

ReviewRound6RegressionTest > ISSUE2_numeric_fingerprint_element_rejected FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:356

ReviewRound6RegressionTest > ISSUE2_numeric_namespace_rejected FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:377

ReviewRound6RegressionTest > ISSUE2_numeric_package_name_rejected FAILED
    org.opentest4j.AssertionFailedError at ReviewRound6RegressionTest.kt:398

ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [1] "delegate_permission/common.handle_all_urls", true PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [2] "delegate_permission/common.handle_all_urls", false PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [3] "delegate_permission/common.handle_all_urls", {"key": "value"} PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [4] "delegate_permission/common.handle_all_urls", ["nested"] PASSED
ReviewRound6RegressionTest > ISSUE2_control_valid_all_string_relation_array_verifies PASSED
```

### Output after fix (commit `eb7ab0a`)
```
ReviewRound6RegressionTest > ISSUE2_numeric_relation_element_rejected_on_pure_verifier PASSED
ReviewRound6RegressionTest > ISSUE2_numeric_relation_element_rejected_on_upspa_verifier PASSED
ReviewRound6RegressionTest > ISSUE2_numeric_fingerprint_element_rejected PASSED
ReviewRound6RegressionTest > ISSUE2_numeric_namespace_rejected PASSED
ReviewRound6RegressionTest > ISSUE2_numeric_package_name_rejected PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [1] "delegate_permission/common.handle_all_urls", true PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [2] "delegate_permission/common.handle_all_urls", false PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [3] "delegate_permission/common.handle_all_urls", {"key": "value"} PASSED
ReviewRound6RegressionTest > ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier > [4] "delegate_permission/common.handle_all_urls", ["nested"] PASSED
ReviewRound6RegressionTest > ISSUE2_control_valid_all_string_relation_array_verifies PASSED
```

---

## Full Test Suite Results

Run on branch `furkan/android-identity-verifier` (`.\gradlew clean test`):
```
BUILD SUCCESSFUL in 10s
5 actionable tasks: 5 executed
```

Per-class test counts from `build/test-results/test/*.xml`:
```xml
<testsuite name="org.upspa.assetlinks.crypto.CertificateUtilsTest" tests="4" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.model.OriginTest" tests="5" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.model.TypedValuesTest" tests="5" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.review.ReviewRound5RegressionTest" tests="39" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.review.ReviewRound5TypedOutcomeTest" tests="16" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.review.ReviewRound6RegressionTest" tests="19" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.verifier.AdversarialNegativeControlTest" tests="27" skipped="0" failures="0" errors="0" />
<testsuite name="org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifierTest" tests="10" skipped="0" failures="0" errors="0" />
```

Total: **125 tests, 0 failures, 0 errors, 0 skipped**.
All 106 existing tests from previous rounds remained green without modification.

---

## Repository Security Gates

Executed from repository root `C:\Users\Furkan\projects\UpSPA_extension` using Python 3.14.8:

### 1. Security Gate Scanner
Command:
```powershell
python tools/security-gates/run_gates.py .
```
Output:
```
Mobile security gates in C:\Users\Furkan\projects\UpSPA_extension
Gates run: accessibility_clipboard, backup_config, exported_components, network_cleartext, pending_intent, screenshot_recents, secret_logging, uniffi_secret_fields

[PASS] no findings from any gate.
```
Exit code: `0`

### 2. Negative Fixture Self-Test
Command:
```powershell
python tools/security-gates/test_negative_fixtures.py
```
Output:
```
Running 8 seeded-negative fixture(s)...

[PASS] accessibility_clipboard: fails for the intended reason, and only that gate fires (1 finding(s)).
[PASS] backup_config: fails for the intended reason, and only that gate fires (1 finding(s)).
[PASS] exported_components: fails for the intended reason, and only that gate fires (2 finding(s)).
[PASS] network_cleartext: fails for the intended reason, and only that gate fires (2 finding(s)).
[PASS] pending_intent: fails for the intended reason, and only that gate fires (3 finding(s)).
[PASS] screenshot_recents: fails for the intended reason, and only that gate fires (2 finding(s)).
[PASS] secret_logging: fails for the intended reason, and only that gate fires (5 finding(s)).
[PASS] uniffi_secret_fields: fails for the intended reason, and only that gate fires (7 finding(s)).

All fixtures isolated and correct.
```
Exit code: `0`

### 3. Positive Fixture Self-Test
Command:
```powershell
python tools/security-gates/test_positive_fixtures.py
```
Output:
```
Running 1 seeded-positive fixture(s)

[PASS] uniffi_secret_fields: correctly produces no findings from any gate.

All positive fixtures stayed clean.
```
Exit code: `0`
