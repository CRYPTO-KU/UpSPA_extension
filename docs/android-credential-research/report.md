# Android Credential Integration and Compatibility — Research Report

**Track owner:** Emirhan  
**Question:** Can Android’s supported credential APIs provide an extension-like UpSPA
experience inside existing native applications?  
**Date:** 2026-08-28  
**Artifact:** [`research/android-credential-lab/`](../../research/android-credential-lab/README.md)  
**This is research, not the production UpSPA client.** No protocol code, no network, no
real secrets. Filled values are deterministic `FAKE-` strings.

Citations `[S-n]`, `[A-n]`, `[C-n]` resolve to [source-ledger.md](source-ledger.md).

---

## 1. Answer (executive)

**Yes, with qualifications — Autofill Framework as the primary path, Credential Manager as
an additive API 34+ path.**

Android can show a locked placeholder in a third-party app, authenticate in a provider
Activity, then write values into that app’s views **without the app integrating UpSPA**.
That is the extension-like property. It is implemented with `AutofillService` +
`FillResponse.setAuthentication` [S-1][S-2], reproduced on **API 26, 30, and 34** (manual
EXP-001) plus API 34 EXP-002/003.

Credential Manager (`CredentialProviderService`) is a better *shape* for locked, deferred
credentials [S-3], but it is **app-initiated**. It cannot replace ambient coverage.

**Instagram-style go/no-go:** **GO for a dropdown locked-entry experience** on View-based
login screens (API 26 / 30 / 34). **NO-GO** for inline keyboard chips (Session B QUALIFIED:
dropdown only; no AOSP IME on lab images) and for unmeasured Chrome / real Instagram.

---

## 2. Requirements (from UpSPA)

1. Ambient coverage: third-party apps, no SDK required (browser-extension analogue).
2. Locked static entry: never pre-derive TOPRF material at suggestion time.
3. Slow derivation after user gesture: `onFillRequest` has a **5 second** AOSP deadline
   [S-7]; derivation must live in an Activity, not in that callback.

---

## 3. Alternatives compared

Full matrix: [autofill-vs-credential-manager.md](autofill-vs-credential-manager.md).

| | AutofillService (API 26+) | CredentialProviderService (API 34+) |
| --- | --- | --- |
| Who starts the flow | System, on focus | App, `getCredential()` |
| Coverage without app changes | Yes | No |
| Locked entry | `setAuthentication` — **lab PASS** | `AuthenticationAction` — stub only |
| Derivation after auth | Auth Activity — **lab PASS** | Entry Activity — designed in |
| Identity | Package name; provider must bind to origin | `CallingAppInfo` / `getOrigin()` [S-3] |
| Passkeys | None | Native |

**Rejected alternative:** AccessibilityService filling. Play policy lists password managers
as **not** accessibility tools and requires narrower APIs when they exist
([policy](https://support.google.com/googleplay/android-developer/answer/10964491)). A-1
already treats a11y autofill as a phishing/hidden-field hazard. Autofill *is* the narrower
API. See source ledger “Rejected alternative.”

---

## 4. Evidence package (minimum bar)

| Requirement | Status |
| --- | --- |
| ≥5 primary sources | **10** (S-1…S-10) |
| 2 security analyses | A-1 CCS 2018 hidden fields / phishing; A-2 AutoSpill / WebView |
| 2 inspected codebases | C-1 AOSP AutofillFramework sample `990d01c`; C-2 Bitwarden Android `d23d1d1` |
| Reproducible prototype | Two-module lab, `assembleDebug` verified |
| Rejected alternative | Accessibility filling — evaluated, rejected |
| Disproof attempt | Compose erosion (EXP-003) — **qualifies** Autofill-primary, does not kill it |

Lab execution (2026-08-28, Pixel_8 AVD, API 34):

- EXP-001 automated: **FAIL** (Espresso raced empty username after dataset click).
- EXP-001 manual Logcat: **PASS**.
- EXP-002, EXP-003 automated: **PASS**.

API 26 and API 30: **PASS** (manual EXP-001, same `EMAIL` T2 + `PASSWORD_CURRENT` T2 as API 34). Keyboard: **QUALIFIED** (no chips shown; no AOSP IME). Chrome: **not executed**.

---

## 5. Recommendations per assigned topic

| Topic | Recommendation |
| --- | --- |
| AutofillService | **Adopt as the production fill surface.** Locked response + `AuthActivity` (`FLAG_SECURE`, biometric / device credential). Never derive inside `onFillRequest`. Honor `CancellationSignal`; always `onSuccess`/`onFailure` [S-1]. |
| CredentialProviderService | **Ship as a second service on API 34+**, same backend, for apps that call Credential Manager and for passkeys. Do not make it the only path. |
| Autofill vs CredMan | Autofill primary, CredMan additive. Android 15 `PendingGetCredentialRequest` [S-8] is still **app-initiated** — it does not restore ambient coverage. |
| Inline keyboard suggestions | Treat as **best-effort UX** from API 30, IME-dependent [S-5]. Do not block the product on chips. Dropdown is the compatibility floor. |
| Field detection | Keep the **3-tier** classifier (standards → heuristics+poison veto → password topology). Never read `ViewNode.getText()`. Skip `GONE`/`INVISIBLE`/disabled nodes [A-1]. Document English-centric Tier 2 regexes as a known gap. |
| AssistStructure parsing | Classify the **last** fill context; keep earlier contexts for split login. Cheap parse only (5 s [S-7]). Bitwarden [C-2]: parse off-binder, catch `TransactionTooLargeException`. |
| WebView / browsers | **Do not fill mixed WebView+native trees as one login** without origin checks [A-2]. Chrome third-party autofill is settings/compat-mode sensitive [C-2]. Plan a dedicated origin policy before any browser claim. |
| Multi-screen login | Offer per step (EXP-002 PASS). Next: assert `fillContexts` history and `setClientState`. |
| Custom views / autofill-disabled | Support virtual structure if the app implements it (fixture exists). `importantForAutofill=no` is a hard miss — same as an extension on a page that blocks autofill. |
| OEM / keyboards | Expect dropdown everywhere; chips only on cooperating IMEs. Unmeasured in lab. |
| Locked account presentation | One locked row, no secrets in `RemoteViews`. Materialize `Dataset` only after auth (`EXTRA_AUTHENTICATION_RESULT`). Same pattern as C-1 sample, minus the sample’s text logging. |
| Minimum Android version | **minSdk 26.** Justify 34+ only as CredMan/passkey *addition*. |
| Real-app limitations | View XML logins: GO (API 34). Compose: GO **if** fields are wired (EXP-003 Arm B); default 1.7 fields unproven. Production apps: not tested. |
| Derivation latency | Keep `onFillRequest` under ~1 s. Put TOPRF in `AuthActivity` after unlock. Probe high latency with EXP-004 (open). |

---

## 6. Field classification (summary)

See [field-classification-rules.md](field-classification-rules.md). Port of the browser
3-tier model [emirhan-universal-autofill-architecture.md](../emirhan-universal-autofill-architecture.md):

- **Tier 1:** `autofillHints` and HTML `autocomplete` [S-6][S-9] — confidence 1.0.
- **Tier 2:** id/hint/html name corpus + poison veto (`search`, `captcha`, payment, …).
- **Tier 3:** password count → LOGIN / REGISTRATION / PASSWORD_CHANGE; positional username
  fallback when a lone password has no identifier.

C-1’s `DebugService` infers from **node text** and logs it — **do not copy**. C-2 continues
to invest in heuristics (identity fields, 2026) — confirms that a small universal engine
beats a per-app rule dump, which is the same bet as the browser doc.

---

## 7. Lifecycle (Autofill, locked entry)

```
focus → AutofillManager → bind AutofillService → onFillRequest (≤5s)
      → FillResponse.setAuthentication(ids, IntentSender, locked RemoteViews)
      → user taps → AuthActivity (FLAG_SECURE, biometric)
      → derive FAKE / (future) TOPRF
      → EXTRA_AUTHENTICATION_RESULT unlocked FillResponse
      → user taps dataset → views filled
      → optional onSaveRequest (lab ignores values)
```

Diagram source: [autofill-vs-credential-manager.md](autofill-vs-credential-manager.md) §3–4.

---

## 8. Newly discovered risks and open problems

1. **Compose default fields** may be invisible (topic 10). Arm B works; Arm A not pinned.
   Ambient “extension-like” coverage is **toolkit-dependent**, unlike the web extension.
2. **Hidden/disabled fills** are an exfiltration primitive [A-1]. Lab now gates on
   `VISIBLE` + `isEnabled`.
3. **WebView AutoSpill** [A-2]: fill can land on the host app’s native fields.
4. **Chrome origin / compat mode** [C-2]: package name is not a website. UpSPA must not
   derive against `com.android.chrome` as if it were a site.
5. **5 s fill timeout** [S-7]: any network in `onFillRequest` will drop the suggestion.
6. **IME overlay / test flake:** dataset tap vs keyboard (EXP-001 automated). Production UX
   should assume the dropdown can sit under the IME on some devices.
7. **IME chips unproven** — Session B QUALIFIED; LatinIME absent on `google_apis` images. Dropdown works at API 26/30/34.
8. **Play a11y policy:** do not “fall back” to AccessibilityService for filling.
9. **Research bypass** in `AuthActivity` is emulator-only; production must fail closed
   without biometrics/device credential.

---

## 9. Disproof of the preferred recommendation

Attack: Compose erosion makes Autofill-primary a bet on a shrinking View world.

Result: **Not disproved.** Wired Compose (Arm B) filled on API 34. Default Compose remains
an open measurement. The recommendation is **qualified**: Autofill-primary for View apps;
Compose requires semantics (`AutofillNode` / future `contentType`). Credential Manager does
not fix unwired Compose either, unless the app also calls `getCredential()`.

Browser and OEM-keyboard attacks were not executed; they can still degrade the
Instagram-style claim without changing the API choice.

---