# Source Ledger — Android Credential Research

Every claim in the research report must trace back to an entry here. AI-generated output is a
discovery aid only; it never counts as a source. Citation keys: `S-n` = primary source,
`A-n` = security analysis, `C-n` = inspected codebase.

Lab cross-checks live in [experiment-log.md](experiment-log.md) and
[compatibility-matrix.md](compatibility-matrix.md) **v0.3** (2026-08-28): EXP-001 locked
path **PASS** on API 26 / 30 / 34.

## Primary sources (official documentation and platform source)

Accessed 2026-08-22 unless noted.

| # | Title | URL | Claims it supports | Lab / later check |
| --- | --- | --- | --- | --- |
| S-1 | AutofillService — API reference | https://developer.android.com/reference/android/service/autofill/AutofillService | Fill lifecycle (bind → onConnected → onFillRequest → onDisconnected); exactly one `FillCallback.onSuccess`/`onFailure` per request or the request times out and is discarded. | Reproduced: `onConnected` / `fillRequest` / `onDisconnected` on API 26, 30, 34. |
| S-2 | Build autofill services — developer guide | https://developer.android.com/guide/topics/text/autofill-services | Manifest, `setAuthentication`, SaveInfo, compatibility-package. | Locked `setAuthentication` **PASS** on 26/30/34 (research bypass + `authAndDerive`). |
| S-3 | Integrate Credential Manager with your credential provider solution | https://developer.android.com/identity/sign-in/credential-provider | Provider APIs Android 14+; `AuthenticationAction`; `CallingAppInfo` / `getOrigin()`. | Provider **stub only** — not an e2e lab pass (EXP-006 open). |
| S-4 | Credential Manager FAQ | https://developer.android.com/identity/sign-in/credential-manager-faq | Jetpack client: Android 4.4+ general, passkeys Android 9+; Play services module on older devices. | Desk evidence only. |
| S-5 | Integrate autofill with keyboards (inline suggestions) | https://developer.android.com/guide/topics/text/ime-autofill | Inline chips API 30+ only if the IME implements the API. | Session B **QUALIFIED**: dropdown works; chips not demonstrated; no AOSP LatinIME on lab `google_apis` images. |
| S-6 | WHATWG HTML Standard — Autofill | https://html.spec.whatwg.org/multipage/form-control-infrastructure.html#autofill | `username`, `email`, `current-password`, `new-password`, `one-time-code`. | WebView fixture **QUALIFIED** (not clean dual-T1 on the spec form). |
| S-7 | AOSP `RemoteFillService.java` | https://android.googlesource.com/platform/frameworks/base/+/master/services/autofill/java/com/android/server/autofill/RemoteFillService.java | `TIMEOUT_REMOTE_REQUEST_MILLIS = 5s`. | All lab `onFillRequest` times 4–363 ms, well under 5 s. |
| S-8 | Integrate Credential Manager with autofill | https://developer.android.com/identity/autofill/credential-manager-autofill | Android 15 + androidx.credentials 1.5.0+: `PendingGetCredentialRequest` is **app-initiated**. | Desk evidence; does not restore ambient coverage. |
| S-9 | AssistStructure.ViewNode | https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode | `autofillHints`, `htmlInfo`, `inputType`, `idEntry`, `hint`, `visibility`, `isEnabled`, `autofillType`. | Session A classifications; unhinted registration email stayed `UNKNOWN` (`textEmailAddress` unused). |
| S-10 | Authenticate with passwords (Credential Manager) | https://developer.android.com/identity/passwords | Passwords via Jetpack from API 19; `android:isCredential` on 14+. | Desk evidence. |
| S-11 | Google Play — Use of the AccessibilityService API | https://support.google.com/googleplay/android-developer/answer/10964491 | Password managers are **not** accessibility tools; use narrower APIs when they exist. Accessed 2026-08-28. | Grounds rejected alternative (below). |

## Security analyses

| # | Title | Venue / Year | URL | Accessed | Key finding relevant to UpSPA |
| --- | --- | --- | --- | --- | --- |
| A-1 | Aonzo, Merlo, Tavella, Fratantonio — "Phishing Attacks on Modern Android" | ACM CCS 2018 | https://doi.org/10.1145/3243734.3243778 (PDF: https://www.s3.eurecom.fr/projects/modern-android-phishing/ccs18-modern-phishing.pdf) | 2026-08-22 | Package↔domain mapping flaws; **hidden-fields** exfil — lab skips `GONE`/`INVISIBLE`/disabled nodes. |
| A-2 | Gangwal, Singh, Srivastava — "AutoSpill" | ACM CODASPY 2023 (Best Paper); Black Hat EU 2023 | https://doi.org/10.1145/3577923.3583658 | 2026-08-22 | WebView autofill can leak into the **host app’s native fields**. Lab WebView **QUALIFIED**; AutoSpill itself **not** instrumented. |

## Inspected codebases

Published source only (no decompilation). Pinned 2026-08-28.

| # | Repository URL | Release / Tag / Commit | License | Relevant files | Useful patterns | Unsafe / unsuitable patterns |
| --- | --- | --- | --- | --- | --- | --- |
| C-1 | https://github.com/android/input-samples | Tree `990d01c` (parent of deprecation `d1479fb`, 2025-07-10). `AutofillFramework/afservice/.../simple/DebugService.java` | Apache-2.0 | `DebugService.java`, `SimpleAuthActivity` | `FillResponse.setAuthentication(ids, intentSender, presentation)` then datasets after auth. Recursive `AssistStructure` walk. | **Not a production reference** (class javadoc). Infers from `ViewNode.getText()` and **logs text**. Fills any enabled node. No origin binding. Repo deprecated. |
| C-2 | https://github.com/bitwarden/android | `d23d1d1706bc53d4ebc063b4d869ff4af1007a0b` (`main`, 2026-08-28, PR #7233) | GPL-3.0 | Autofill `Job` (PR #3545); `TransactionTooLargeException` (PR #3569); Chrome compat-mode vs origin (issue #5789) | Honor the 5 s fill budget; cancel on timeout; browser origin ≠ package name. | Chrome third-party autofill is settings/compat-mode dependent. Heuristics are an ongoing product surface. |

C-3 (KeePassDX) was a candidate; **not** inspected — C-1 and C-2 meet the two-codebase bar.

## Rejected alternative (fully evaluated)

| Alternative | Why considered | Why rejected | Evidence |
| --- | --- | --- | --- |
| AccessibilityService filling (pre-API-26 PM approach) | Works on arbitrary trees; no Autofill floor | Play policy: password managers are not accessibility tools and must use narrower APIs (Autofill *is* that API) [S-11]. A-1: a11y/hidden-field phishing. UpSPA needs locked entry + deferred derivation — a11y typing has no `setAuthentication` / 5 s callback contract. | [S-11], A-1, lab Autofill path PASS on API 26/30/34 |

## Disproof attempt (explicit)

| Preferred recommendation | Attack | Result (as of 2026-08-28) |
| --- | --- | --- |
| Autofill primary, Credential Manager additive ([autofill-vs-credential-manager.md](autofill-vs-credential-manager.md) §9) | **Compose erosion.** Default Compose fields invisible → ambient coverage shrinks. | **Qualified, not rejected.** Session A Arm A: **no `fillRequest`** / no offer (Compose 1.7 default = invisible). Arm B / hinted Compose: **T1 PASS**. View XML login **PASS** on API 26/30/34. Production must require autofill semantics on Compose (`AutofillNode` / `contentType`) or miss a growing share of apps. CredMan does not fix unwired Compose unless the app also calls `getCredential()`. |
| Same | **IME / Instagram chips (Session B).** | **Qualifies UX, not the API choice.** Dropdown locked-entry **GO**. Inline chips **QUALIFIED** (not shown). AOSP LatinIME **N/A** on lab images. |
| Same | **Chrome / AutoSpill (EXP-007).** | **Still open.** Lab WebView QUALIFIED only. Does not overturn Autofill-primary for native View apps. |
