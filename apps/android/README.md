# UpSPA Android Walking Skeleton

This is the first production-repository template, not the earlier research lab.

It currently proves only the Android platform boundary:

1. A controlled fixture app exposes nine Autofill scenarios.
2. `UpspaAutofillService` classifies the screen and returns one locked UpSPA entry, or nothing.
3. Selection opens a screenshot-protected UpSPA Activity.
4. The Activity returns clearly synthetic values through the system Autofill contract.

The template intentionally:

- has no `INTERNET` permission;
- does not collect a master password;
- does not invoke protocol operations;
- does not persist account or credential data;
- does not claim that Credential Manager is implemented beyond registration of the API 34 stub.

## Modules

- `:app` — containing app, Autofill service, field classifier, API 34 provider stub, secure
  Activity, and fake engine.
- `:fixtures` — a separate-package set of controlled forms; no screen reads, submits, logs, or
  stores what is typed into it.

The Android code stays in these two modules until the G1 vertical slice stabilizes the package
boundaries. Security-critical cross-platform boundaries are already separate Rust crates.

## Prerequisites

- JDK 17 or newer
- Android SDK 35
- Android build tools accepted by AGP 8.7.3
- `ANDROID_HOME` pointing at the SDK, or a `local.properties` containing `sdk.dir`

### Windows: the checkout path must be ASCII

The Android Gradle Plugin refuses to build when the project path contains non-ASCII characters,
and the check cannot be disabled from the command line. On Windows, clone to a path such as
`C:\src\UpSPA_extension`. A path containing characters such as `ü` or `İ` fails with:

```
Your project path contains non-ASCII characters.
```

## Build and test

All commands are written to run from the repository root.

```bash
# Unit tests: the classifier rules and the locked-response policy
./apps/android/gradlew -p apps/android :app:testDebugUnitTest

# Both debug APKs
./apps/android/gradlew -p apps/android :app:assembleDebug :fixtures:assembleDebug
```

On Windows PowerShell, use the batch wrapper:

```powershell
.\apps\android\gradlew.bat -p apps\android :app:testDebugUnitTest
.\apps\android\gradlew.bat -p apps\android :app:assembleDebug :fixtures:assembleDebug
```

The HTML test report is written to
`apps/android/app/build/reports/tests/testDebugUnitTest/index.html`.

## Verify on a device

Install both debug APKs, enable the UpSPA Autofill service, then walk the fixture screens by hand.
The full procedure, expected decisions, and API 26 / 30 / 34 results are in
[docs/emirhan-android-autofill-fixtures-classifier.md](../../docs/emirhan-android-autofill-fixtures-classifier.md).

Every value UpSPA fills contains `UPSPA-TEMPLATE` or the reserved `.invalid` domain and must never
be used as a credential.

## Next implementation step

Generate the compatibility corpus from this repository's
`packages/extension/src/shared/passwordPolicy.ts`, port that encoder to Rust, and replace
`TemplateCredentialEngine` with the reviewed UniFFI command/effect boundary. Do not import the
different User Study encoder.
