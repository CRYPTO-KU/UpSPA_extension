# Android Autofill fixtures and classifier

Original implementation: Emirhan. Maintainer follow-up: 28 September 2026.
Target branch: `mobile-dev`. Assignment: AND-02, AND-04, AND-06.

## Scope and boundaries

This walking skeleton expands the controlled fixture app, tests Autofill field classification,
and preserves the generic locked response. All returned values are synthetic. The Autofill
callback performs no network, persistence, derivation, or master-password work. Real accounts,
production credentials, full Credential Manager integration, and universal app compatibility
remain outside this PR.

The production changes are in `apps/android/app/.../autofill`, the adjacent
`secureui/CredentialAuthActivity.kt`, and `apps/android/fixtures`. JUnit is added to the app's
build and version catalog. The extra settings plugin and Gradle sync property from the original
submission have been removed; neither shared file differs from `mobile-dev`. IDE metadata is
removed and ignored.

The branch incorporates merged PRs #26 and #28. Their combination exposed a security-gate false
positive in the encoder corpus test's generic logging. That test now accumulates failed vector
IDs and uses one assertion containing only those IDs and a generic mismatch. It does not print
actual or expected passwords; no gate was weakened and no production Rust code changed.

## Classification and locked response

`AssistStructureAdapter` copies structural metadata into `ViewNodeSnapshot`. It never reads node
text or user-entered values. The classifier works on snapshot trees so the rules can be tested
on the JVM without constructing framework `ViewNode` objects.

1. Collection requires an Autofill ID, text Autofill type, visible/enabled node, and an eligible
   `importantForAutofill` mode. Traversal stops at hidden ancestors and at containers marked
   `NO_EXCLUDE_DESCENDANTS` or `YES_EXCLUDE_DESCENDANTS` (the latter may itself be eligible).
2. The shared veto rejects poison terms and explicit Android/AndroidX payment/address hints or
   HTML payment/address autocomplete field tokens. It runs before credential hints, input-type
   fallback, and topology promotion. Mixed credential and payment hints are rejected regardless
   of ordering. HTML tokens are case-normalized and separated on ASCII whitespace; `section-*`,
   `shipping`, and `billing` are grouping tokens, not field types.
3. The physical-address poison check excludes only the phrase `email address` (including
   `e-mail address` and identifier spellings such as `email_address`). A separate street/postal
   address signal in another attribute still vetoes the field.
4. Tier 1 maps explicit credential hints; tier 2 uses attribute metadata and input type;
   tier 3 infers missing username and current/new-password roles from form topology. The same
   veto prevents topology from reviving rejected fields.

The attribute corpus includes developer-authored IDs, labels, hints, and descriptions. It excludes
field values. Diagnostics report roles/tiers and fixed refusal reasons, without attribute text.
`LockedResponsePolicy.DEFAULT` returns either no response or one generic authentication entry;
that entry has no credential-value field. `CredentialAuthActivity` uses `FLAG_SECURE` and returns
only template values. Fields without Autofill IDs are removed while keeping IDs and roles aligned.

Relevant specifications: [AndroidX hint constants](https://developer.android.com/reference/androidx/autofill/HintConstants)
and [HTML autocomplete tokens](https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#autofill).

## Fixture matrix

| Scenario | Expected behavior |
| --- | --- |
| Login | Username and current password |
| Registration | Email, new password, confirmation |
| Password change | Current password, new password, confirmation |
| Split login | Only the identifier or password present on that step |
| No hints | Username inferred before a lone password |
| Hidden/disabled | Only visible, enabled credential fields |
| Poison fields alongside login | Login fields only; payment/search/coupon/postal fields stay empty |
| Autofill disabled | No response |
| Unknown fields | No response |

The fixtures are a separate package with stable XML resource IDs. Submit buttons show a fixed
Toast; they do not read, submit, log, or persist form contents.

## Validation of the maintainer revision

Run from the repository root with JDK 17 and Android SDK 35 configured:

```bash
./apps/android/gradlew -p apps/android :app:testDebugUnitTest
./apps/android/gradlew -p apps/android :app:assembleDebug :fixtures:assembleDebug
python3 tools/security-gates/run_gates.py .
python3 tools/security-gates/test_negative_fixtures.py
python3 tools/security-gates/test_positive_fixtures.py
cargo test -p upspa-core --test vectors_password_encoder
```

| JVM suite | Tests | Failures |
| --- | ---: | ---: |
| FieldClassifierTest | 30 | 0 |
| FieldClassifierReviewRegressionTest | 11 | 0 |
| FieldClassifierNegativeControlTest | 9 | 0 |
| LockedResponsePolicyTest | 5 | 0 |
| Total | 55 | 0 |

Both debug APKs assemble. All eight security gates pass, all eight negative fixture families
fail only their intended gate, and the positive fixture remains clean. The Rust encoder corpus
suite passes four tests.

Before fixing production code, the review regressions plus existing classifier suite reproduced
10 failing tests out of 41: the real registration label, email variants, masked payment/address
hints, conflicting hints, topology promotion, whitespace-separated HTML hints, and hidden
ancestors. The final suite includes 14 more tests than the previously submitted 41-test suite.

The negative controls compare safe defaults with deliberately weakened policies:

| Control | Guard weakened | Unsafe behavior demonstrated |
| --- | --- | --- |
| NC-1 | Poison terms | Payment/search fields become fillable, including through topology |
| NC-2 | Visibility/enabled | Hidden/disabled nodes and visible children under hidden ancestors become fillable |
| NC-3 | Autofill importance | An explicitly excluded subtree becomes fillable |
| NC-4 | Authentication | Template values appear in the pre-authentication decision |
| NC-5 | Non-credential hints | Masked payment fields and an address field through topology become fillable |

The service always uses the default policy. Weakened policies exist only as in-module testing seams.

## Device coverage and manual verification

The original author reported the following manual coverage for the earlier implementation.
These runs were not repeated for the 28 September maintainer revision, and are not evidence that
this revision was exercised on-device. The classification fixes above were verified by JVM tests.

| API | Originally reported device | Original report | Maintainer revision |
| --- | --- | --- | --- |
| 26 | API_26, x86 | Pass | Not run |
| 30 | Pixel_5, x86_64 | Pass | Not run |
| 34 | Pixel_8, x86_64 | Pass | Not run |

To verify on-device:

1. Install `apps/android/app/build/outputs/apk/debug/app-debug.apk` and
   `apps/android/fixtures/build/outputs/apk/debug/fixtures-debug.apk`.
2. Enable UpSPA Autofill from the app or Android settings.
3. Open each fixture. Credential screens must offer exactly one generic locked entry with no
   account name or value preview. Select it, confirm screenshot protection, and continue with
   synthetic values.
4. Verify registration includes its email field. Confirm payment/search/postal fields, hidden
   and disabled fields, Autofill-disabled screens, and unknown screens receive no values.
5. Record each API/device result separately; mark unavailable runs as not run.

## Limitations and deferred work

- WebView metadata availability varies. No WebView or Compose device fixtures were added.
- Custom views need virtual Autofill structures. OEM traversal and keyboard behavior vary.
- Inline suggestions and production password derivation are not implemented.
- On API 26/27, `importantForAutofill` is unavailable on `ViewNode`; the adapter reports null and
  relies on the platform to withhold opted-out nodes. Visible/enabled gates still apply.
- Attribute matching remains heuristic, not universal app support or an identity proof.
- On Windows use an ASCII checkout path for the Android Gradle Plugin.
- Later integration replaces `TemplateCredentialEngine` with the reviewed UniFFI boundary and
  adds instrumented system-dropdown tests, WebView/Compose fixtures, and Credential Manager work.
