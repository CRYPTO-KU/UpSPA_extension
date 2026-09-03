# Experiment Log — Android Credential Research

One entry per experiment run. Evidence rules:

- No real credentials anywhere. Screenshots must only ever show `FAKE-` values.
- Log excerpts come from the structured tags (`UpSpaAutofill`, `UpSpaAuth`,
  `UpSpaCredProvider`, `UpSpaLatency`) — never from raw structure dumps.
- Record enough environment detail that someone else can reproduce the run with the
  lab project at the same commit.

## Entry template

```markdown
### EXP-<nnn> — <short title>

- Date:
- Topic(s): <1-11 from the tracker README>
- Hypothesis:
- Environment:
  - Lab commit:
  - Device/AVD: <name + system image, e.g. upspa-api26 / android-26;google_apis;x86>
  - API level:
  - Keyboard: <Gboard version / AOSP LatinIME>
  - Simulated derivation latency: <ms>
  - Fixture + hinted switch: <e.g. Registration, hints OFF>
- Procedure:
  1.
- Observation:
- Evidence: <logcat excerpt / screenshot path under evidence/>
- Verdict: <supports / contradicts / inconclusive — and what changes in the report>
```

## Automation

EXP-001, EXP-002, and EXP-003 are automated in
`research/android-credential-lab/fixtures/src/androidTest/java/com/upspa/research/fixtures/AutofillExperimentTest.kt`
(Espresso for in-app views, UIAutomator for the system fill UI and the provider's
AuthActivity). Run on a connected, lock-screen-less emulator:

```
./gradlew :autofill-provider:installDebug
./gradlew :fixtures:connectedDebugAndroidTest
adb logcat -s UpSpaExperiment UpSpaAutofill UpSpaLatency   # observation lines
```

An automated pass still gets a log entry below: record the environment, paste the
`UpSpaExperiment` lines as evidence, and state the verdict.

## Entries

### EXP-001 — Baseline XML login, locked-entry round trip (automated)

- Date: 2026-08-28
- Topic(s): 1, 3, 4, 8, 11
- Hypothesis: Heuristic (Tier 2/3) classification of the unhinted XML login screen is enough for a locked entry to appear, and the auth Activity then fills `FAKE-` values.
- Environment:
  - Lab commit: `cf185ba` (working tree also includes the later test-flake fix)
  - Device/AVD: Pixel_8(AVD) — Android 14 (API 34)
  - API level: 34
  - Keyboard: emulator default (not recorded)
  - Simulated derivation latency: default (0 ms)
  - Fixture + hinted switch: `LoginActivity`, hints OFF
- Procedure:
  1. `./gradlew :autofill-provider:installDebug` then `./gradlew :fixtures:connectedDebugAndroidTest` from `%TEMP%\upspa-android-lab`
  2. Test focuses `loginUsername`, waits for locked entry "UpSPA Research", taps it, taps Research bypass, taps dataset "Fill as FAKE-user-…", asserts both fields start with `FAKE-`
- Observation:
  - Automated run **FAILED** at the final Espresso assertion: `loginUsername` did not start with `FAKE-user-` immediately after the dataset click. Locked entry, AuthActivity bypass, and dataset row *did* appear (the test reached the assertion). Gradle: `Tests 2/3 completed (0 failed)` then EXP-001 failed; EXP-002 and EXP-003 passed. Build failed in 1m 5s.
  - **Manual Logcat run on the same AVD: PASS** — both fields received `FAKE-` values. The fill path is real; the automated check raced AutofillManager applying values (and/or the IME overlaying the dataset tap).
- Evidence: Gradle report `fixtures/build/reports/androidTests/connected/debug/index.html` on the Windows lab copy; Espresso error `AssertionFailedWithCauseError` matching `a string starting with "FAKE-user-"`. Manual confirmation via `adb logcat -s UpSpaAutofill UpSpaAuth UpSpaLatency`.
- Verdict: **Supports** the locked-entry architecture (manual). Automated flake **does not contradict** the hypothesis. Test updated to `closeSoftKeyboard()` on focus and to poll field text for up to 12 s.

### EXP-002 — Split login offers on both steps (automated)

- Date: 2026-08-28
- Topic(s): 6
- Hypothesis: Identifier-only and password-only screens each produce a fill offer (Tier 3 LOGIN).
- Environment: same Pixel_8(AVD) API 34 connectedDebugAndroidTest run as EXP-001
- Procedure: `AutofillExperimentTest.exp002_splitLogin_lockedEntryOfferedOnBothSteps`
- Observation: **PASS** (automated). Locked entry shown on step 1 and step 2.
- Evidence: Gradle connected test output — tests 1/3 and 2/3 completed with 0 failed before EXP-001's assertion; EXP-002 is the split-login test and completed successfully.
- Verdict: **Supports** multi-screen offer-per-step. Cross-step `fillContexts` contents still unexamined (EXP-002 extension).

### EXP-003 — Compose default vs explicit AutofillNode wiring (automated)

- Date: 2026-08-28
- Topic(s): 10
- Hypothesis: Compose 1.7 default fields may be invisible to AutofillService (Arm A, observation only). Explicit `AutofillNode` wiring must surface a locked entry (Arm B, hard assertion).
- Environment: same Pixel_8(AVD) API 34 run
- Procedure: `AutofillExperimentTest.exp003_composeLogin_defaultVsExplicitAutofillVisibility`
- Observation: **PASS** (automated). Arm B (explicit wiring) showed the locked entry. Arm A is a logged observation (`offer shown = true/false`), not a hard fail — the logcat `UpSpaExperiment` line was not captured in this session, so Arm A Compose 1.7 invisibility is **not yet a pinned measurement**.
- Evidence: Gradle connected test output (EXP-003 did not fail).
- Verdict: **Supports** that explicit Compose autofill wiring is sufficient on API 34. **Does not yet confirm** default-Compose invisibility. This is the main remaining disproof angle for “autofill primary.”

### EXP-001 / 002 / 003 — Manual logcat (API 34, 2026-08-28 15:58–16:02)

- Date: 2026-08-28
- Topic(s): 1, 3, 4, 6, 8, 10, 11
- Hypothesis: Same as EXP-001..003; operator completed the UI flows by hand.
- Environment:
  - Device/AVD: Pixel_8(AVD) — Android 14 (API 34), provider pid 6706
  - Simulated derivation latency: 0 ms (authAndDerive 103 ms wall time)
  - Hinted switch: OFF for XML + split; ON for Compose Arm B
- Procedure: Manual guide (enable provider → fixtures). Operator reported all UI flows passed (`FAKE-` fill).
- Observation:
  1. **Unhinted XML login (EXP-001):** `intent=LOGIN fields=[EMAIL(tier=2,conf=0.6), PASSWORD_CURRENT(tier=2,conf=0.6)]`. Identifier classified as **EMAIL** not USERNAME (English `email` hint regex). Still LOGIN; locked path used. `research bypass used`. `onFillRequest` 363 ms (cold); `authAndDerive` 103 ms.
  2. **Split step 1:** `USERNAME(tier=2,conf=0.6)` only, fill 11 ms.
  3. **Split step 2:** `PASSWORD_CURRENT(tier=2,conf=0.6)` only, fill 15 ms.
  4. **Compose hinted / Arm B:** `USERNAME(tier=1,conf=1.0), PASSWORD_CURRENT(tier=1,conf=1.0)`, fill 6 ms — AutofillNode wiring is Tier 1.
  5. **Compose Arm A (default fields):** no `fillRequest` in this capture. Either not focused, or the provider never classified candidates (consistent with Compose 1.7 invisibility, **inconclusive** without a dedicated Arm A note).
- Evidence: operator logcat `adb logcat -s UpSpaAutofill UpSpaAuth UpSpaLatency` (no field values).
- Verdict: **Supports** locked Autofill path, split-login per-step offers, and wired Compose. Classifier nuance: unhinted login username can land as EMAIL under Tier 2; report should not claim USERNAME-only. Latency: classification stays well under the 5 s [S-7] budget.

### Session A — remaining fixtures (API 34, 2026-08-28 18:48–18:56)

- Date: 2026-08-28
- Topic(s): 3, 4, 5, 7, 10
- Hypothesis: Per [field-classification-rules.md](field-classification-rules.md) §8 fixture table.
- Environment: Pixel_8(AVD) API 34, provider pid 12754, logcat `UpSpaAutofill` / `UpSpaLatency` only (no field values).
- Procedure: Manual Session A (hints ON/OFF as specified). Operator: Compose Arm A (2nd test) **passed** (no offer).
- Observation (in time order):
  1. **XML login, hints ON:** `LOGIN [USERNAME(t=1), PASSWORD_CURRENT(t=1)]` — 172 ms. **PASS** (matches T1 prediction).
  2. **Compose Arm A (hints OFF, default fields):** no `fillRequest` between 18:48 and 18:51. Operator reported pass (no locked row). **PASS** for “Compose 1.7 default fields invisible.”
  3. **Registration, hints OFF:** `REGISTRATION [UNKNOWN(t=0), PASSWORD_NEW(t=3), PASSWORD_NEW(t=3)]` (twice). Intent and two-new-password topology **PASS**. Identifier **UNKNOWN**, not EMAIL T2 as predicted — **QUALIFIED**. (`inputType=textEmailAddress` is not a classifier signal today.)
  4. **Registration, hints ON:** `REGISTRATION [EMAIL(t=1), PASSWORD_NEW(t=1), PASSWORD_NEW(t=1)]`. **PASS**.
  5. **Password change, hints OFF:** `PASSWORD_CHANGE [PASSWORD_CURRENT(t=3), PASSWORD_NEW(t=3), PASSWORD_NEW(t=3)]`. Intent **PASS**; current password is T3 topology, not T2 “current” marker. **QUALIFIED** vs the T2-marker row in the rules doc.
  6. **Password change, hints ON:** all three T1. **PASS**.
  7. **Custom view:** `LOGIN [USERNAME(t=2), PASSWORD_CURRENT(t=2)]`. **PASS**.
  8. **WebView (three LOGIN bursts):** `EMAIL(t=2)+PASSWORD_CURRENT(t=2)`; then `USERNAME(t=1)+PASSWORD_CURRENT(t=2)`; then `USERNAME(t=2)+PASSWORD_CURRENT(t=2)`. Form 2 heuristics **PASS**. Form 1 not clean T1 on both fields (password stayed T2 once). **QUALIFIED**. AutoSpill (native vs HTML target) not instrumented.
  9. **No-autofill screen:** no matching `fillRequest` in this capture. **NOT RUN** (cannot score from this log).
- Verdict: Session A closes XML-T1, Compose Arm A, registration, password-change, custom view, and WebView-enough-for-a-cell. Open at the time: no-autofill, keyboards, API 26/30, CredMan e2e, Chrome.

### Session C — EXP-001 on API 26 (2026-08-28 20:09)

- Date: 2026-08-28
- Topic(s): 8, 9, 11
- Hypothesis: Locked Autofill path works at the Autofill Framework floor (API 26).
- Environment: API 26 AVD, provider pid 5217. XML login, hints OFF.
- Procedure: Focus username (two fill requests), research bypass, complete locked flow.
- Observation:
  - Both fills: `LOGIN [EMAIL(t=2,0.6), PASSWORD_CURRENT(t=2,0.6)]` (12 ms then 7 ms) — same unhinted classification as API 34.
  - `research bypass used`; `authAndDerive` 33 ms.
- Evidence: operator logcat (no field values).
- Verdict: **PASS**. minSdk 26 is no longer docs-only.

### Session C — EXP-001 on API 30 (2026-08-28 20:25)

- Date: 2026-08-28
- Topic(s): 8, 9, 11
- Hypothesis: Same locked path on the inline-suggestions API floor (API 30).
- Environment: API 30 AVD, provider pid 4436. XML login, hints OFF.
- Procedure: Focus username, research bypass.
- Observation:
  - `LOGIN [EMAIL(t=2,0.6), PASSWORD_CURRENT(t=2,0.6)]`, fill 181 ms.
  - `research bypass used`; `authAndDerive` 58 ms.
- Evidence: operator logcat (no field values).
- Verdict: **PASS**. Same classifier output as API 26/34.

### Session B — Keyboard / inline chips (2026-08-28)

- Date: 2026-08-28
- Topic(s): 2
- Hypothesis: Gboard shows inline chips on API 30+; AOSP LatinIME is dropdown-only [S-5].
- Environment: Lab `google_apis` images (API 30 and/or 34). Operator result: **QUALIFIED**; **no AOSP keyboard** in Settings.
- Observation: Locked dropdown / EXP-001-style fill is available. Inline suggestion chips were **not** demonstrated. LatinIME comparison arm **cannot run** on these system images.
- Verdict: **QUALIFIED**. Do not claim Instagram-style IME chips. Dropdown is the compatibility floor across 26/30/34.
