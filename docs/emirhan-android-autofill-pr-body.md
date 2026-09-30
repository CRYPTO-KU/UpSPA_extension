## Scope

Android Autofill fixtures and classifier for `mobile-dev` (AND-02, AND-04, AND-06).
Expands the controlled app to nine scenarios and makes classification and the locked-response
policy testable on the JVM. Values remain synthetic; no real credentials, networking, account
persistence, or production derivation are introduced.

## Review fixes

- Keep registration fields labeled “Email address” fillable while still rejecting physical-address signals.
- Veto explicit Android/AndroidX and HTML payment/address hints before all credential classification,
  including topology fallback; handle conflicting hints and HTML token whitespace/case.
- Stop traversal through GONE/INVISIBLE ancestors, even when descendants report VISIBLE.
- Add regressions and negative controls for these rules; preserve the generic locked response.
- Remove committed IDE metadata and unrelated Gradle configuration changes.

The branch includes the merged #26/#28 changes. A small integration fix replaces noisy Rust corpus
logging with one assertion reporting only failed vector IDs and a generic mismatch, allowing the
merged security gates to pass without weakening them.

## Changed boundaries

Android Autofill code/tests, the fixture app, adjacent authentication ID/role alignment, JUnit
build dependencies, ignore rules, and documentation. The integration fix touches only
`crates/upspa-core/tests/vectors_password_encoder.rs`. No production Rust code, security gate,
Gradle settings, or dependency lockfile is changed relative to current `mobile-dev`.

## Validation

- 55 JVM tests pass (30 classifier, 11 review regressions, 9 negative controls, 5 locked-response).
- Both `:app:assembleDebug` and `:fixtures:assembleDebug` pass.
- All eight mobile security gates pass; eight negative fixture families and the positive fixture pass.
- All four Rust encoder corpus tests pass.
- Before the fixes, the targeted classifier run reproduced 10 failures; the new regression cases
  now pass. Negative controls still demonstrate unsafe results when individual guards are disabled.

Commands:

```bash
./apps/android/gradlew -p apps/android :app:testDebugUnitTest :app:assembleDebug :fixtures:assembleDebug
python3 tools/security-gates/run_gates.py .
python3 tools/security-gates/test_negative_fixtures.py
python3 tools/security-gates/test_positive_fixtures.py
cargo test -p upspa-core --test vectors_password_encoder
```

## Manual coverage and limitations

The original author reported manual API 26/30/34 passes. Those device runs were **not repeated**
for this maintainer revision; its classifier changes were verified by JVM tests. WebView/Compose
fixtures, inline suggestions, instrumented dropdown tests, and production FFI integration remain
deferred. Attribute classification remains heuristic.

Manual demo: install both debug APKs, enable UpSPA Autofill, and walk the nine fixtures. Only
credential fields should offer the generic locked entry; continuing through the secure Activity
fills synthetic values. Payment/address, hidden/disabled, opted-out, and unknown fields stay empty.

See [the implementation report](docs/emirhan-android-autofill-fixtures-classifier.md) for the
fixture matrix, negative controls, commands, and device verification procedure.
