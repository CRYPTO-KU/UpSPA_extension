# Android Credential Integration and Compatibility — Research Track

Owner: Emirhan. Deliverables tracker: can Android’s native APIs provide an extension-like
experience for UpSPA’s single-password authentication?

Ground rules:

- Existing architectural recommendations (including
  [emirhan-universal-autofill-architecture.md](../emirhan-universal-autofill-architecture.md))
  are **hypotheses** — confirm, modify, qualify, or reject based on evidence.
- Primary evidence only: official docs, academic papers, security analyses, inspected source.
  AI-generated answers are discovery aids and must be verified against primary sources.
- No real credentials or secrets in experiments, logs, screenshots, or fixtures — the
  prototype uses `FAKE-`prefixed deterministic test data.

Reproducible evidence artifact: [`research/android-credential-lab/`](../../research/android-credential-lab/README.md)

**Headline (2026-08-28):** Autofill locked-entry path **PASS** on API **26 / 30 / 34**. minSdk
**26**. Credential Manager additive on 34+ (stub only). Instagram-style **dropdown = GO**;
**IME chips = QUALIFIED / no-go as a claim**.

## Deliverables checklist

| # | Deliverable | Status | Artifact |
| --- | --- | --- | --- |
| 1a | Research report | **v1, updated 2026-08-28** | [report.md](report.md) |
| 1b | Short presentation | **PPTX (12 slides)** | [presentation.pptx](presentation.pptx); also [presentation.html](presentation.html), [presentation-outline.md](presentation-outline.md) |
| 2 | Source ledger | populated (S-1…S-10, A-1/A-2, C-1/C-2) | [source-ledger.md](source-ledger.md) |
| 3 | Comparison of two alternatives | evidence-resolved | [autofill-vs-credential-manager.md](autofill-vs-credential-manager.md) |
| 4 | Reproducible prototype | lab + manual logcat on 26/30/34 | [lab README](../../research/android-credential-lab/README.md), [experiment-log.md](experiment-log.md) |
| 5 | Recs per question + risks | in report | [report.md](report.md) §§5–8 |
| 6 | Minimum evidence package | **met** | [source-ledger.md](source-ledger.md) |
| 7 | Output assets | **v0.3 matrix** | this folder |

### Minimum evidence package

- [x] 5 primary sources — 10 pinned (S-1…S-10)
- [x] 2 security analyses — Aonzo CCS 2018 (A-1); AutoSpill CODASPY 2023 (A-2)
- [x] 2 inspected codebases — input-samples AutofillFramework `990d01c` (C-1); Bitwarden Android `d23d1d1` (C-2)
- [x] 1 rejected alternative — AccessibilityService filling (Play policy)
- [x] 1 disproof attempt — Compose erosion (Arm A invisible, Arm B T1); Autofill-primary **qualified**, not rejected

### Required output assets

- [x] Android API comparison — [autofill-vs-credential-manager.md](autofill-vs-credential-manager.md)
- [x] Field-classification rules — [field-classification-rules.md](field-classification-rules.md)
- [x] Lifecycle/callback diagrams — mermaid in the comparison doc; [report.md](report.md) §7
- [x] Compatibility matrix — [compatibility-matrix.md](compatibility-matrix.md) **v0.3** (API 26/30 EXP-001 **PASS**; IME chips **QUALIFIED**)
- [x] Minimum Android version — **API 26**, lab-backed on three AVDs
- [x] Instagram-style go/no-go — **GO** dropdown locked-entry on View logins (26/30/34); **NO-GO** as a chip/Chrome/Instagram-app claim

## Research topics → evidence

| # | Topic | Status | Where |
| --- | --- | --- | --- |
| 1 | Autofill vs Credential Manager | Autofill primary; CredMan stub / additive | comparison doc, report |
| 2 | Inline keyboard / OEM IME | **QUALIFIED** — dropdown yes; chips not shown; no AOSP IME on `google_apis` images | matrix §3, Session B in experiment log |
| 3 | Login / registration / password-change | Session A: T1 PASS; unhinted registration email UNKNOWN (QUALIFIED); password-change T3 | experiment log Session A |
| 4 | AssistStructure parsing | Classifier live; never reads `ViewNode` text | `FieldClassifier.kt` |
| 5 | WebView / browsers | Lab WebView **QUALIFIED**; Chrome **NOT RUN** | matrix §4 |
| 6 | Multi-screen login | EXP-002 **PASS** (offer on both steps) | experiment log |
| 7 | Custom views / autofill-disabled | Custom view **PASS**; No-autofill **NOT RUN** | matrix §2 |
| 8 | Locked entry + AuthActivity | **PASS** on 26/30/34 (`research bypass` + `authAndDerive`) | experiment log Session C |
| 9 | Minimum API | **26** — EXP-001 PASS on 26, 30, 34 (same `EMAIL` T2 + `PASSWORD_CURRENT` T2 when hints off) | matrix §1 |
| 10 | Compose / real apps | Arm A invisible **PASS**; Arm B T1 **PASS**; no production apps | Session A, EXP-003 |
| 11 | Derivation latency | Fill 7–363 ms; derive 33–103 ms at 0 ms knob; under 5 s [S-7] | `UpSpaLatency` lines |

Logcat tags: `UpSpaAutofill`, `UpSpaAuth`, `UpSpaLatency`, `UpSpaExperiment`.
