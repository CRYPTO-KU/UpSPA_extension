START HERE — Emirhan, Android Credential Integration (UpSPA research)

This zip is RESEARCH, not the production UpSPA app. Do not enter real passwords.
Filled values in the lab are FAKE-user- / FAKE-pw- only.

================================================================
1. Read these files (in this order)
================================================================

  docs/android-credential-research/report.md
      Full answer: Autofill primary (minSdk 26), Credential Manager additive
      on API 34+. Instagram-style: GO for dropdown locked-entry; NO-GO for
      claiming inline keyboard chips or real Instagram/Chrome.

  docs/android-credential-research/presentation.pptx
      PowerPoint deck — 12 widescreen slides. Use this in the review.

  docs/android-credential-research/presentation.html
      Same deck in a browser if PowerPoint is unavailable.

  docs/android-credential-research/presentation-outline.md
      Speaker notes.

  docs/android-credential-research/compatibility-matrix.md
      v0.3 — EXP-001 PASS on API 26, 30, and 34. IME chips QUALIFIED.

  docs/android-credential-research/experiment-log.md
      Logcat evidence (roles/latency only; no field secrets).

  docs/android-credential-research/source-ledger.md
      Official docs, papers, inspected repos, rejected a11y filling.

  docs/android-credential-research/autofill-vs-credential-manager.md
  docs/android-credential-research/field-classification-rules.md

  docs/emirhan-universal-autofill-architecture.md
      Browser 3-tier hypothesis the Android classifier ports.

  docs/android-credential-research/README.md
      Tracker and remaining gaps (No-autofill fixture, Chrome, CredMan e2e).

================================================================
2. Reproducible lab (do not skip this folder)
================================================================

  research/android-credential-lab/

  Two apps: autofill-provider (com.upspa.research.provider) and
  fixtures (com.upspa.research.fixtures). Isolated Gradle project.

  Do NOT build from a \\\\wsl.localhost UNC path on Windows. Copy the lab
  folder to the Windows disk (e.g. %TEMP%\upspa-android-lab), then:

    gradlew.bat :autofill-provider:installDebug
    gradlew.bat :fixtures:installDebug

  Enable "UpSPA Research" as the device autofill service, open the fixtures
  app, Login, hints OFF. Logcat:

    adb logcat -s UpSpaAutofill UpSpaAuth UpSpaLatency

  If "adb" is not on PATH:

    %LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe

================================================================
3. Headline findings (one paragraph)
================================================================

Android AutofillService can show a locked placeholder in a third-party app,
authenticate in the provider, then fill FAKE- values — without the target
app integrating UpSPA. That path was reproduced on API 26, 30, and 34.
CredentialProviderService is a better lock/defer shape but is app-initiated
and remains a stub in this lab. Default Jetpack Compose 1.7 fields were
invisible to the provider; explicit AutofillNode wiring was Tier 1.

================================================================
4. What is not in this zip
================================================================

  - Production UpSPA / TOPRF code
  - PPTX slides (use presentation-outline.md)
  - Screenshots (optional; any you add must show only FAKE- values)
  - local.properties, .gradle/, build/ (do not include if you re-zip)

Questions: Emirhan — Android credential track.
Date: 2026-08-28
