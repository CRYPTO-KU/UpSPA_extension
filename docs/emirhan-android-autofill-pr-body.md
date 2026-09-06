# PR body: Android Autofill fixtures and classifier

Copy the section below into the pull request description. It targets `mobile-dev`.

---

## Assignment scope and roadmap IDs

Task 1 (Emirhan) — Android Autofill fixtures and classifier. Roadmap IDs: TBD, to be filled in by
the maintainer.

Scope: expand the controlled fixture app, add classifier tests, preserve the locked response, keep
synthetic values only, record API 26 / 30 / 34+ coverage, document platform limitations, and
provide a negative control proving the poison-field protection can fail when weakened.

## Files and architectural boundaries changed

Owned by this task:

- `apps/android/app/src/main/java/com/upspa/mobile/autofill/` — `ViewNodeSnapshot.kt` (new),
  `AssistStructureAdapter.kt` (new), `LockedResponsePolicy.kt` (new), `FieldClassifier.kt`,
  `UpspaAutofillService.kt`
- `apps/android/app/src/test/java/com/upspa/mobile/autofill/` — new, 4 files
- `apps/android/fixtures/` — 9 layouts, 3 Kotlin files, manifest, strings
- `apps/android/README.md`, `docs/emirhan-android-autofill-fixtures-classifier.md`

Adjacent, same Autofill path:

- `apps/android/app/src/main/java/com/upspa/mobile/secureui/CredentialAuthActivity.kt` — filters
  fields without an autofill id so the id and role arrays stay index-aligned. No change to the
  security model.

Shared integration files:

- `apps/android/gradle/libs.versions.toml` — **one additive change**: `junit = "4.13.2"` plus the
  matching library entry. Nothing existing modified or removed.
- `apps/android/app/build.gradle.kts` — `testImplementation(libs.junit)` and
  `testOptions { unitTests.isReturnDefaultValues = true }`.
- `apps/android/settings.gradle.kts` — **not modified**, no new module.
- `Cargo.toml`, `Cargo.lock` — **not modified**.

No other contributor's module, dependency, test, or security gate was removed or weakened.

## Implementation summary

`FieldClassifier` previously walked `AssistStructure` directly, which has no public constructor and
no usable Robolectric shadow, so none of its rules could be tested off-device. The rules now run on
a platform-independent `ViewNodeSnapshot` tree produced by a single adapter, and the classifier is
parameterized by a `Policy` whose guards default to safe and can only be weakened from inside the
module. `FieldClassifier.classify(AssistStructure)` is unchanged for callers.

`LockedResponsePolicy` makes the pre-authentication contract structural: a `LockedEntry` has no
member capable of holding a credential value, so "one generic entry before the protected Activity"
is now a property a test can check rather than a claim in a comment.

The fixture app grew from one login form to nine scenarios behind a launcher, using XML layouts
with named ids. That last part was required, not cosmetic: the old fixture used
`View.generateViewId()`, which produces no `idEntry`, so tier-2 corpus matching could never fire on
a device.

`UpspaAutofillService` now logs refusals as explicitly as offers
(`no response target=... reason=<token>`). This is a deliberate divergence from the agreed plan:
without it, "correctly refused" and "crashed" are indistinguishable in logcat, which would make
scenarios 8 and 9 unverifiable. The reason is one of five fixed tokens and nothing derived from
screen content is logged.

## Build and test commands

From the repository root:

```bash
./apps/android/gradlew -p apps/android :app:testDebugUnitTest
./apps/android/gradlew -p apps/android :app:assembleDebug :fixtures:assembleDebug
```

Windows PowerShell:

```powershell
.\apps\android\gradlew.bat -p apps\android :app:testDebugUnitTest
.\apps\android\gradlew.bat -p apps\android :app:assembleDebug :fixtures:assembleDebug
```

## Actual test results

```
com.upspa.mobile.autofill.FieldClassifierTest:                tests=28 failures=0 errors=0 skipped=0
com.upspa.mobile.autofill.FieldClassifierNegativeControlTest: tests=6  failures=0 errors=0 skipped=0
com.upspa.mobile.autofill.LockedResponsePolicyTest:           tests=5  failures=0 errors=0 skipped=0
BUILD SUCCESSFUL
```

39 tests, 0 failures, 0 skipped, from a clean `--rerun-tasks` run.
`:app:assembleDebug` and `:fixtures:assembleDebug` both succeed.

**Device coverage: pass on API 26, 30, and 34.** Every fixture scenario was walked by hand on
`API_26`, `Pixel_5`, and `Pixel_8`. The locked UpSPA entry appeared only on credential screens;
poison, hidden, disabled, Autofill-disabled, and unknown fields stayed unfilled. See section 8 of
the report.

## Negative-control procedure and result

Four guards have controls in `FieldClassifierNegativeControlTest`: the poison veto (NC-1), the
visibility and enabled gates (NC-2), the `importantForAutofill` gate (NC-3), and the
pre-authentication lock (NC-4). Each disables one guard through a module-internal constructor and
asserts the unsafe outcome, paired with the safe outcome under `Policy.DEFAULT`.

To show the shipped default is the guard doing the work, replace `Policy.DEFAULT_POISON` in
`FieldClassifier.kt` with `Regex("(?!)")` and run the unit tests. Observed:

```
FieldClassifierNegativeControlTest > NC-1 weakening the poison veto also lets topology promote a search box FAILED
FieldClassifierNegativeControlTest > NC-1 default policy refuses a card security code and a search box FAILED
FieldClassifierTest > checkout fields are refused while the sign-in box is still offered FAILED
FieldClassifierTest > the topology fallback refuses to promote a poison field FAILED
FieldClassifierTest > a poison term outranks an identifier term in the same corpus FAILED
FieldClassifierTest > a masked payment field is refused even though it looks like a password FAILED
39 tests completed, 6 failed
```

Assertion messages name the fields that leaked, including
`expected:<[]> but was:<[card_cvc]>` and
`expected:<[poison_login_username, poison_login_password]> but was:<[poison_card_cvv, poison_login_username, poison_login_password]>`.
The suite returned to 39 passing after reverting.

## Manual demonstration steps

Full procedure in section 9 of
[the report](emirhan-android-autofill-fixtures-classifier.md). Summary:

1. Build and install both APKs, then enable the UpSPA Autofill service.
2. Open fixture **1. Login** and confirm exactly one entry, **Unlock UpSPA Mobile (template)**,
   with no account name and no value preview.
3. Select it, confirm the unlock Activity blocks screenshots (`FLAG_SECURE`), continue, and confirm
   the fields receive clearly synthetic values.
4. Walk **2. Registration**, **3. Password change**, **4. Split login**, and **5. No hints**. Each
   offers the same generic entry and fills only its credential fields.
5. Open **7. Poison fields** and focus the search, coupon, card number, card security code, and
   postal code fields. No UpSPA entry may appear on any of them. Fill the login box and confirm
   those five fields stay empty.
6. Open **6. Hidden and disabled fields** and confirm the disabled password stays empty after a fill.
7. Open **8. Autofill explicitly disabled** and **9. Unknown fields**. No UpSPA entry may appear.

This walk was completed on API 26, 30, and 34.

## Security considerations

- No credential value exists in the pre-authentication decision; the type has no field for one.
- Poison matches are refused outright, excluded from the authentication id array and from the
  topology fallback, and the veto runs before the input-type rule so a masked card security code
  cannot be treated as a password.
- Invisible, gone, and disabled fields are refused, so nothing is written where a user cannot see it.
- The application's `importantForAutofill` opt-out is enforced in UpSPA's own code, not only by the
  platform.
- Logging is roles, tiers, requesting package, and a fixed refusal token. A test asserts the summary
  contains no attribute text.
- All values are synthetic (`UPSPA-TEMPLATE...`, `.invalid`). No real, derived, or reusable secret
  appears in the code, the tests, or the documentation.
- `onSaveRequest` still inspects nothing. `:app` has no `INTERNET` permission; `:fixtures` has none.

## Known limitations

WebView `htmlInfo` availability varies by WebView build; custom views without a virtual structure
cannot be filled at all; Compose semantics-based autofill is not covered and the fixtures are
View-based on purpose; OEM traversal of `GONE` and `importantForAutofill` nodes varies, which is why
UpSPA re-checks both; inline suggestions (API 30+) are not implemented; `getImportantForAutofill()`
does not exist below API 28, so on API 26 and 27 UpSPA relies on the platform having withheld
excluded nodes.

**Windows blocker:** AGP refuses to build when the checkout path contains non-ASCII characters, and
`-Pandroid.overridePathCheck=true` does not work as a command-line flag because AGP reads that
property only from `gradle.properties`. Documented in the README as a clone-path requirement rather
than committing the override to a shared file. All results above were produced from an ASCII-path
build of this exact tree.

## Deferred integration work

Replace `TemplateCredentialEngine` with the UniFFI command/effect boundary; implement Credential
Manager beyond the API 34 stub; add instrumented `androidTest` coverage that drives the system
dropdown; add WebView and Compose fixtures; implement `InlineSuggestionsRequest`.

## Confirmations

- [ ] This PR targets `mobile-dev`.
- [ ] The branch is synchronized with `mobile-dev`, conflict-free, and mergeable.
- [ ] Verified from a clean checkout at an ASCII path.
- [ ] No other contributor's module, dependency, test, or security gate was removed.
