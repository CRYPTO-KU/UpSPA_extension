# UpSPA Android Walking Skeleton

This is the first production-repository template, not the earlier research lab.

It currently proves only the Android platform boundary:

1. A controlled fixture exposes username and password fields.
2. `UpspaAutofillService` returns one locked UpSPA entry.
3. Selection opens a screenshot-protected UpSPA Activity.
4. The Activity returns clearly synthetic values through the system Autofill contract.

The template intentionally:

- has no `INTERNET` permission;
- does not collect a master password;
- does not invoke protocol operations;
- does not persist account or credential data;
- does not claim that Credential Manager is implemented beyond registration of the API 34 stub.

## Modules

- `:app` — containing app, Autofill service, API 34 provider stub, secure Activity, and fake engine.
- `:fixtures` — a separate-package controlled login form; it never submits or logs values.

The Android code stays in these two modules until the G1 vertical slice stabilizes the package
boundaries. Security-critical cross-platform boundaries are already separate Rust crates.

## Prerequisites

- JDK 17
- Android SDK 35
- Android build tools accepted by AGP 8.7.3

## Build

From this directory:

```bash
./gradlew :app:assembleDebug :fixtures:assembleDebug
```

Install both debug APKs, open UpSPA Mobile Bootstrap, enable its Autofill service, then open the
fixture and focus a field. Every filled value contains `UPSPA-TEMPLATE` or the reserved
`.invalid` domain and must never be used as a credential.

## Next implementation step

Generate the compatibility corpus from this repository's
`packages/extension/src/shared/passwordPolicy.ts`, port that encoder to Rust, and replace
`TemplateCredentialEngine` with the reviewed UniFFI command/effect boundary. Do not import the
different User Study encoder.
