# Compatibility matrix — Android credential research

Status: **v0.3 (2026-08-28)**. Session A (API 34 fixtures) + Session B (keyboard,
QUALIFIED) + Session C (EXP-001 on API 26 and API 30).

Lab floor: `minSdk 26`, `targetSdk 35`. Provider APK + fixture APK, cross-package.

## 1. API-level matrix (topic 9)

| Surface | API 26 | API 30 | API 34 (Pixel_8 AVD) |
| --- | --- | --- | --- |
| AutofillService bind + `onFillRequest` | **PASS** (20:09: `LOGIN` EMAIL T2 + PASSWORD_CURRENT T2, 12 ms) | **PASS** (20:25: same classification, 181 ms) | **PASS** (EXP-001 locked entry; EXP-002/003 automated) |
| Locked `FillResponse.setAuthentication` | **PASS** (research bypass + `authAndDerive` 33 ms) | **PASS** (bypass + `authAndDerive` 58 ms) | **PASS** (manual EXP-001); automated EXP-001 raced the fill apply |
| CredentialProviderService | N/A (API 34+) [S-3] | N/A | Stub only — **NOT RUN** e2e (EXP-006 open) |
| Inline IME chips | N/A (API 30+) [S-5] | **QUALIFIED** (Session B: dropdown path; chips not demonstrated) | **QUALIFIED** (Session B) |

**Minimum-version recommendation:** **API 26**, now **lab-backed** on three AVDs (26 / 30 / 34)
for the locked XML-login path. Do not raise the floor to 34 for Credential Manager: that
would drop every pre-14 device and every non-integrated app [S-3][S-4]. Classification on
unhinted login was the same on all three levels (`EMAIL` T2 + `PASSWORD_CURRENT` T2).

## 2. Fixture / classification matrix (API 34 only)

| Fixture | Hints | Predicted (rules doc) | API 34 evidence |
| --- | --- | --- | --- |
| XML login | OFF (Tier 2/3) | LOGIN, username+password | **PASS** manual (logcat: EMAIL T2 + PASSWORD_CURRENT T2, then bypass + derive). Automated assertion still flaked. |
| XML login | ON (Tier 1) | LOGIN via `autofillHints` | **PASS** (18:48: `USERNAME`+`PASSWORD_CURRENT` T1) |
| Split login step 1 | OFF | LOGIN, identifier only | **PASS** automated + manual (`USERNAME` T2 only) |
| Split login step 2 | OFF | LOGIN, password only | **PASS** automated + manual (`PASSWORD_CURRENT` T2 only) |
| Compose Arm A (default 1.7 fields) | n/a | no candidates | **PASS** (operator: no offer; no `fillRequest` in the 18:48–18:51 window) |
| Compose Arm B (`AutofillNode`) | wired | LOGIN, T1 | **PASS** automated + manual (`USERNAME`+`PASSWORD_CURRENT` T1) |
| Registration | OFF | REGISTRATION; email T2 + 2× PASSWORD_NEW T3 | **QUALIFIED** — intent + two `PASSWORD_NEW` T3 correct; email stayed `UNKNOWN` T0 (18:51) |
| Registration | ON | REGISTRATION T1 | **PASS** (`EMAIL`+2×`PASSWORD_NEW` T1, 18:52) |
| Password change | OFF | PASSWORD_CHANGE; current T2 + 2× new T3 | **QUALIFIED** — intent correct; all three T3 (18:53), not T2 marker on current |
| Password change | ON | PASSWORD_CHANGE T1 | **PASS** (all T1, 18:53) |
| WebView `login.html` | mixed | form1 T1, form2 T2 LOGIN | **QUALIFIED** (18:55–18:56): T2/T2, mixed T1 username + T2 password, then T2/T2. Not a clean dual-T1 form 1. |
| Custom virtual view | — | LOGIN | **PASS** (`USERNAME`+`PASSWORD_CURRENT` T2, 18:54) |
| No-autofill screen | pruned | no offer | **NOT RUN** (absent from this logcat) |

## 3. Keyboard matrix (topic 2)

| IME | API 30 | API 34 | Notes |
| --- | --- | --- | --- |
| Gboard (or image default IME) | **QUALIFIED** | **QUALIFIED** | Session B: locked dropdown / fill path works; **inline chips not demonstrated**. Instagram-style chip UX remains unproven. |
| AOSP LatinIME | **N/A** | **N/A** | Not present on these `google_apis` images (no AOSP keyboard option in Settings). |
| OEM IME | — | — | Out of lab scope until a physical device pass |

**Dropdown locked entry** is proven on API 26, 30, and 34. **Inline chips** stay QUALIFIED /
unproven.

## 4. Browser / WebView (topic 5)

| Host | Third-party AutofillService | Evidence |
| --- | --- | --- |
| Lab WebView fixture | **QUALIFIED** | AssistStructure reached the provider (LOGIN lines 18:55–18:56). Password not always T1 on the spec form. AutoSpill (fill into host native fields) **not** measured. |
| Chrome / Brave native autofill | **NOT RUN** | Bitwarden [C-2] #5789: leaving compat mode can drop web origin on Basic Auth |
| Real Instagram / other Play apps | **NOT RUN** | Deliberately out of the isolated lab; do not use real accounts |

## 5. Real-application survey (topic 10)

The Compose fixture (Arm A invisible, Arm B T1) is the stand-in for modern-toolkit
compatibility. No production apps were filled. No real credentials were used.