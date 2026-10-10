# PR #27 Review Fixes — Verification & Reproduction Report

This report answers the review request: reproduce each issue on the previous head with synthetic
fixtures, show a regression test that fails before the fix, address it, and report commands and results.
All output below is copied from real runs on the author's machine (Windows, Git Bash). Nothing was
edited by hand except trimming unrelated lines.

## How to reproduce

| Step | Commit | What it contains |
|---|---|---|
| Unfixed code | `6e6bc05` | previous head of this PR |
| Tests only | `ca006c8` | adds `ReviewRound5RegressionTest.kt`, production code unchanged |
| Fixes | `801f6fa` | the five production fixes, `ReviewRound5TypedOutcomeTest.kt`, one changed existing test |
| Merged with `mobile-dev` | `e1debc1` | merge commit, no conflicts |

To see the failures: check out `ca006c8` and run, from `upspa-assetlink-verifier/`:
```
./gradlew test --tests '*ReviewRound5RegressionTest*'
```
To see the fixed state: check out `801f6fa` (or this branch head) and run `./gradlew clean test`.

## Summary

| Issue | Failing tests before fix | Tests passing after fix |
|---|---|---|
| 1. Rotation lineage repair | 2 | all |
| 2. Requested package binding | 1 | all |
| 3. Malformed JSON (null elements) | 12 | all |
| 4. Certificate bytes factory | 2 | all |
| 5. IPv6 host validation parity | 4 | all |
| **Total** | **21 of 39** | **39 of 39** |

Control tests (valid single signer, valid history, matching package, valid certificate, valid IPv6 hosts)
pass both before and after the fix.

---

## Issue 1: Rotation lineage repair vs rejection
Fix: `AppSigningInfo.fromTypedValues` now rejects a supplied non-empty rotation history that does not contain
all current signers (`IllegalArgumentException`) instead of appending the missing signer.

Command:
```
./gradlew test --tests '*ReviewRound5RegressionTest*'
```

### Output before fix
```
ReviewRound5RegressionTest > ISSUE1_fromTypedValues_rejects_inconsistent_history FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:142

ReviewRound5RegressionTest > ISSUE1_end_to_end_statement_authorizing_A_never_verifies_inconsistent_history_app FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:188

ReviewRound5RegressionTest > ISSUE1_control_consistent_history_constructs_and_verifies PASSED
ReviewRound5RegressionTest > ISSUE1_control_no_history_verifies_against_current_signer PASSED
```

### Output after fix
```
ReviewRound5RegressionTest > ISSUE1_fromTypedValues_rejects_inconsistent_history PASSED
ReviewRound5RegressionTest > ISSUE1_end_to_end_statement_authorizing_A_never_verifies_inconsistent_history_app PASSED
ReviewRound5RegressionTest > ISSUE1_control_consistent_history_constructs_and_verifies PASSED
ReviewRound5RegressionTest > ISSUE1_control_no_history_verifies_against_current_signer PASSED
```

---

## Issue 2: Requested package binding
Fix: the default method `AssetLinkVerifier.verify(requestedIdentity, appSigningInfo)` now compares
`requestedIdentity.packageName` with `appSigningInfo.packageName` first and returns the new typed rejection
`VerificationResult.Rejected.RequestedPackageMismatch` without any fetch. The test double counts fetches.

### Output before fix
```
ReviewRound5RegressionTest > ISSUE2_requested_package_mismatch_must_reject_and_never_fetch FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:276

ReviewRound5RegressionTest > ISSUE2_control_matching_package_verifies_and_fetches PASSED
```

### Output after fix
```
ReviewRound5RegressionTest > ISSUE2_requested_package_mismatch_must_reject_and_never_fetch PASSED
ReviewRound5RegressionTest > ISSUE2_control_matching_package_verifies_and_fetches PASSED
ReviewRound5TypedOutcomeTest > test exact typed outcome RequestedPackageMismatch with package details PASSED
ReviewRound5TypedOutcomeTest > meta-check RequestedPackageMismatch is never produced when package names match PASSED
```

---

## Issue 3: Malformed JSON statements (null elements)
Fix: after parsing, `PureAssetLinkVerifier.verifyRawJson` validates every statement, its `target`, every
`relation` element and every `sha256_cert_fingerprints` element, and returns
`VerificationResult.Rejected.InvalidJsonFormat` instead of failing later. `UpSpaAssetLinkVerifier` uses the same
function, so both entry points are covered.

What was observed before the fix, per input (the test calls the verifier at line 324 and asserts the result type at
line 325 for the pure verifier; lines 337 and 338 for the UpSpa verifier):
- `NullPointerException` thrown by the verifier: `[null]`, a valid statement followed by `null`, `null` followed by a
  valid statement, `sha256_cert_fingerprints` containing `null`.
- A result that was not `InvalidJsonFormat` (no exception): `relation` containing `null`, and `target: null`.
- Already rejected correctly before the fix: a statement that is a JSON string.

### Output before fix
```
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_array_containing_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:324
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_valid_followed_by_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:324
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_null_followed_by_valid FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:324
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_relation_contains_null FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:325
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_fingerprints_contains_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:324
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_target_is_null FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:325
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_statement_is_string PASSED

ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_array_containing_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:337
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_valid_followed_by_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:337
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_null_followed_by_valid FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:337
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_relation_contains_null FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:338
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_fingerprints_contains_null FAILED
    java.lang.NullPointerException at ReviewRound5RegressionTest.kt:337
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_target_is_null FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:338
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_statement_is_string PASSED
```

### Output after fix
```
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_array_containing_null PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_valid_followed_by_null PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_null_followed_by_valid PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_relation_contains_null PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_fingerprints_contains_null PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_target_is_null PASSED
ReviewRound5RegressionTest > ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_pure_verifier_statement_is_string PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_array_containing_null PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_valid_followed_by_null PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_null_followed_by_valid PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_relation_contains_null PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_fingerprints_contains_null PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_target_is_null PASSED
ReviewRound5RegressionTest > ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json > ISSUE3_upspa_verifier_statement_is_string PASSED
```
`ReviewRound5TypedOutcomeTest` additionally asserts the exact `InvalidJsonFormat` outcome for the same 7 inputs on both
verifiers (14 tests); all passed.

---

## Issue 4: Certificate bytes factory validation
Fix: `CertificateDigest.fromCertificateBytes` now requires `CertificateUtils.isValidX509Certificate(bytes)`
(valid and fully consumed) and throws `IllegalArgumentException` otherwise. `CertificateUtils.computeSha256Fingerprint`
is unchanged (an existing test relies on it hashing arbitrary bytes); it now carries a note that it is a generic hash
utility, not evidence validation. One existing test, `TypedValuesTest`, previously passed the bytes of the string
"TestCertData" to the factory; it now uses `CertificateUtils.sampleX509CertificateBytes`.

### Output before fix
```
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_raw_invalid_bytes FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:348
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_truncated_bytes FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:374

ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_appended_trailing_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_empty_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_control_valid_sample_certificate_accepted PASSED
```

### Output after fix
```
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_raw_invalid_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_appended_trailing_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_empty_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_fromCertificateBytes_rejects_truncated_bytes PASSED
ReviewRound5RegressionTest > ISSUE4_control_valid_sample_certificate_accepted PASSED
```

---

## Issue 5: Origin IPv6 host validation parity
Fix: a bracketed host is now accepted only if `java.net.URI` also accepts it as an IPv6 literal. The constructor
path uses this check, and `Origin.parse` calls the same host validation, so both paths apply one rule. Note: a
syntactically valid IPv6 literal with an embedded IPv4 part (`[::ffff:1.2.3.4]`) is rejected by both paths, because
the character whitelist does not allow a dot; this keeps the two paths consistent and is the stricter choice.

### Output before fix
```
ReviewRound5RegressionTest > ISSUE5_direct_constructor_rejects_invalid_ipv6_literal FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:400
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[abcd] FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:427
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[:::] FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:427
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[12345::] FAILED
    org.opentest4j.AssertionFailedError at ReviewRound5RegressionTest.kt:427
```
The other ten host-parity cases (`[::1]`, `[2001:db8::1]`, `[2001:DB8::1]`, `[::1`, `[]`, `[g::1]`,
`[::ffff:1.2.3.4]`, `example.com`, `127.0.0.1`, `a-b.example.com`) passed before and after.

### Output after fix
```
ReviewRound5RegressionTest > ISSUE5_direct_constructor_rejects_invalid_ipv6_literal PASSED
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[abcd] PASSED
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[:::] PASSED
ReviewRound5RegressionTest > ISSUE5_host_validation_parity_between_constructor_and_parse > ISSUE5_host_parity_[12345::] PASSED
```

---

## Whole suite

Before the fix, running only the new test class on the unfixed production code:
```
39 tests completed, 21 failed
```

After the fix, on the branch head merged with `mobile-dev` (`./gradlew clean test`), per-class test counts from
`build/test-results/test/*.xml`, order as printed:
```
tests="4"  skipped="0" failures="0" errors="0"     (CertificateUtilsTest)
tests="5"  skipped="0" failures="0" errors="0"     (OriginTest)
tests="5"  skipped="0" failures="0" errors="0"     (TypedValuesTest)
tests="39" skipped="0" failures="0" errors="0"     (ReviewRound5RegressionTest)
tests="16" skipped="0" failures="0" errors="0"     (ReviewRound5TypedOutcomeTest)
tests="27" skipped="0" failures="0" errors="0"     (AdversarialNegativeControlTest)
tests="10" skipped="0" failures="0" errors="0"     (UpSpaAssetLinkVerifierTest)
```
Total 106 tests, 0 failures, 0 errors. The 51 tests that existed before this round (4 + 5 + 5 + 27 + 10) still pass.
The same totals were also observed on the fixed working tree before merging `mobile-dev`.

## Repository security gates

Run from the repository root on the merged branch (Python 3.14.8):
```
python tools/security-gates/run_gates.py .
python tools/security-gates/test_negative_fixtures.py
python tools/security-gates/test_positive_fixtures.py
```
```
Gates run: accessibility_clipboard, backup_config, exported_components, network_cleartext, pending_intent, screenshot_recents, secret_logging, uniffi_secret_fields
[PASS] no findings from any gate.
exit code: 0

Running 8 seeded-negative fixture(s)...
All fixtures isolated and correct.
exit code: 0

Running 1 seeded-positive fixture(s)
All positive fixtures stayed clean.
exit code: 0
```
These gates scan the Android app and the mobile FFI crate; they do not scan `upspa-assetlink-verifier/`.

## Limitations

- The expected set of failing tests before the fix is documented above; a few cases (`statement_is_string`,
  `appended_trailing_bytes`, `empty_bytes`, and the ten unaffected host cases) already behaved correctly and act as
  controls.
- `computeSha256Fingerprint` still hashes arbitrary non-certificate bytes by design; production certificate ingestion
  paths validate with `isValidX509Certificate` first.
- Valid IPv6 literals containing an embedded IPv4 part are rejected by both `Origin` paths.
