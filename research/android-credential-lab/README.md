# UpSPA Android Credential Research Lab

Isolated, reproducible evidence artifact for the **"Android Credential Integration and
Compatibility"** research track. This is NOT the UpSPA production client: no protocol code is
integrated, nothing is persisted or transmitted, and every filled value is deterministic,
`FAKE-`prefixed test data.

Research deliverables and experiment logs live in the main repo under
[`docs/android-credential-research/`](../../docs/android-credential-research/README.md).

## Modules

| Module | Package | Purpose |
| --- | --- | --- |
| `autofill-provider` | `com.upspa.research.provider` | Minimal `AutofillService` (locked entry + secure `AuthActivity` + fake credential generation + latency probes) and an API 34+ `CredentialProviderService` stub for the comparison track. |
| `fixtures` | `com.upspa.research.fixtures` | Controlled mock screens: login, registration, password change, split login (2 activities), WebView form, Jetpack Compose login (default fields vs explicit AutofillNode wiring), custom view with virtual autofill children, autofill-disabled screen. Separate applicationId so every fill is genuinely cross-package. Includes the automated experiment suite (`src/androidTest`). |

The launcher screen of `fixtures` has a switch that toggles **spec-compliant autofill hints**
per scenario, so Tier 1 (authoritative hints) and Tier 2/3 (heuristics/topology) classification
can be compared on identical layouts.

## Opening the project

The repo lives on the WSL filesystem while Android Studio and emulators usually run on Windows.

- **Do NOT build over the UNC path.** Verified: Windows-side Gradle cannot run against
  `\\wsl.localhost\...` — the wrapper batch file fails (CMD rejects UNC working directories)
  and invoking the wrapper directly fails in Gradle's file hasher
  (`java.io.IOException: Incorrect function` on the WSL 9P filesystem).
- **Option A (verified working):** copy this folder to the Windows filesystem and build there.
  The lab has no dependency on the rest of the monorepo. Example:
  `robocopy \\wsl.localhost\Ubuntu\home\elibol\UpSPA_extension\research\android-credential-lab %TEMP%\upspa-android-lab /E /XD .gradle build`
  then `gradlew.bat assembleDebug` in the copy. Verified with JDK 21 + Gradle 8.9 + AGP 8.7.3:
  `BUILD SUCCESSFUL`, both debug APKs produced.
- **Option B:** build inside WSL (`./gradlew assembleDebug`) — requires a JDK 17+ and an
  Android SDK installed in the WSL distro (not verified yet).

Requirements: Android Studio with SDK Platform 35, JDK 17 (bundled with Studio is fine).

If Gradle sync complains about a missing `gradle/wrapper/gradle-wrapper.jar`, either let
Android Studio use its bundled Gradle, or regenerate the wrapper once with
`gradle wrapper --gradle-version 8.9`, or download the jar pinned to the wrapper version:
`https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar`.

## Three-API test matrix (research topic 9)

| Config | API level | Why this level |
| --- | --- | --- |
| `api26` | 26 (Android 8.0) | Autofill Framework floor — the minimum-version candidate. |
| `api30` | 30 (Android 11) | Inline suggestions / IME integration boundary (`InlinePresentation`). |
| `api34` | 34+ (Android 14) | `CredentialProviderService` floor; Credential Manager comparison arm. |

Both modules define these as **Gradle Managed Devices** (`testOptions.managedDevices` in each
`build.gradle.kts`), which double as reproducible emulator definitions:

```
./gradlew :fixtures:api26DebugAndroidTest   # provisions + boots the API 26 device
./gradlew :fixtures:api30DebugAndroidTest
./gradlew :fixtures:api34DebugAndroidTest
```

**Fallback (manual AVDs):** if a managed-device image is unavailable for your SDK setup
(API 26 images are the most likely gap), create AVDs by hand and record them in the
experiment log:

| AVD name | Device | System image |
| --- | --- | --- |
| `upspa-api26` | Pixel 2 | `system-images;android-26;google_apis;x86` |
| `upspa-api30` | Pixel 4 | `system-images;android-30;google_apis;x86_64` |
| `upspa-api34` | Pixel 8 | `system-images;android-34;google_apis;x86_64` |

## Keyboard matrix (research topic 2)

Inline (IME chip) suggestions require API 30+ AND an IME that implements the inline
suggestions API. Test at least:

1. **Gboard** — supports inline suggestions (present on `google_apis`/Play images).
2. **AOSP LatinIME** — no inline support; forces the dropdown path (available on AOSP images,
   or via Settings → System → Languages & input → On-screen keyboard).

Record keyboard + API level combinations per experiment in
`docs/android-credential-research/experiment-log.md`.

## Running an experiment

1. Install both apps on the target emulator: `./gradlew installDebug` (or Run in Studio).
2. In **UpSPA Research Provider**, tap "Set as autofill service" and confirm.
   - ADB alternative: `adb shell settings put secure autofill_service com.upspa.research.provider/.UpSpaAutofillService`
3. Optionally set the **simulated derivation latency** (models TOPRF round-trips; topic 11).
4. Open **UpSPA Fixture Apps**, pick a scenario, focus a field.
5. Expected flow: locked entry appears → tap → secure `AuthActivity` (biometric / device
   credential / research bypass on lock-screen-less emulators) → fields fill with `FAKE-` values.
6. Collect evidence:
   - Classification + callbacks: `adb logcat -s UpSpaAutofill UpSpaCredProvider UpSpaAuth`
   - Latency measurements: `adb logcat -s UpSpaLatency`

## Automated experiments (EXP-001..003)

`fixtures/src/androidTest/.../AutofillExperimentTest.kt` drives the full locked-entry round
trip with UIAutomator (system fill UI, provider AuthActivity) + Espresso (fixture views):

- **EXP-001** — baseline unhinted XML login: locked entry → research bypass → FAKE fill.
- **EXP-002** — split login: fill offer on both the identifier-only and password-only steps.
- **EXP-003** — Compose: Arm A (default fields, offer presence recorded as an observation)
  vs Arm B (explicit AutofillNode wiring, offer asserted).

Preconditions and launch:

```
# emulator WITHOUT lock screen / biometrics (research bypass must be available)
./gradlew :autofill-provider:installDebug     # test harness checks this and fails fast
./gradlew :fixtures:connectedDebugAndroidTest
adb logcat -s UpSpaExperiment                 # structured observation lines
```

The suite auto-enables the provider via `settings put secure autofill_service ...` (the
instrumentation shell uid holds WRITE_SECURE_SETTINGS). Note: Gradle Managed Devices do not
auto-install the provider APK, so run the suite against a connected emulator.

## Security boundaries (non-negotiable)

- No real credentials, ever — in fields, logs, screenshots, or fixtures.
- The provider never logs field values or `AssistStructure` text, only roles/tiers/latency.
- `AuthActivity` sets `FLAG_SECURE`; the research bypass exists solely for lock-screen-less
  emulators and is loudly logged.
- The HMAC key in `FakeCredentialFactory` is intentionally public; it must never be reused
  outside this lab.
