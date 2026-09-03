# Short presentation — Android credential APIs for UpSPA

**Slides:** [presentation.pptx](presentation.pptx) (PowerPoint, 12 widescreen slides)

Browser fallback: [presentation.html](presentation.html)

**Audience:** architecture review (interns + leads)  
**Length:** ~10 minutes + questions  
**Owner:** Emirhan  
**Date:** 2026-08-28

Do not show any credential except `FAKE-` prefixes. Do not present this lab as the product.

---

## Slide 1 — Question

Can Android’s supported APIs give an **extension-like** UpSPA experience in apps that have
never heard of us?

Three constraints: ambient coverage, **locked** suggestion, **slow** TOPRF after unlock.

## Slide 2 — Two APIs (one decisive difference)

| AutofillService (API 26) | Credential Manager provider (API 34) |
| --- | --- |
| System starts on field focus | App must call `getCredential()` |
| We parse `AssistStructure` | App declares what it wants |
| Dropdown / optional IME chips | System bottom sheet |

**Punchline:** only Autofill is ambient. CredMan is a better lock/defer *shape*, worse
coverage.

## Slide 3 — Locked-entry lifecycle (draw this)

Focus → `onFillRequest` (**5 s**, AOSP `RemoteFillService`) → locked row → Auth Activity
(`FLAG_SECURE`) → derive → dataset → fill.

Derivation **must not** live in `onFillRequest`.

## Slide 4 — Lab evidence (API 34 Pixel 8)

- Isolated two-app lab. No real secrets. Values are `FAKE-user-` / `FAKE-pw-`.
- **Manual EXP-001:** full XML login round trip **works**.
- **Automated EXP-002 / EXP-003:** **PASS** (split login offers; wired Compose).
- **Automated EXP-001:** **FAIL** — Espresso too fast / IME; **not** a product fail.
- API 26 / 30 / Gboard chips / Chrome: **not run**.

## Slide 5 — Classification (do not build a site list)

Same 3-tier bet as the browser engine: hints → heuristics + poison veto → password
topology. Never read field **text**. Skip hidden and disabled nodes (CCS 2018 hidden-field
exfil).

## Slide 6 — Recommendation

1. **minSdk 26**, Autofill as the product fill path.
2. **CredentialProviderService on API 34+** for integrated apps and passkeys — additive.
3. **Reject AccessibilityService filling** (Play policy + phishing surface).
4. Compose: require autofill semantics; do not assume default TextFields are visible.

## Slide 7 — Instagram-style go / no-go

- **GO:** locked dropdown in a third-party **View** login (proven API 34).
- **NO-GO (yet):** “works like the extension everywhere, including Instagram, Compose, and
  inline chips.” Those cells are empty or qualified.

## Slide 8 — Risks to remember

AutoSpill (WebView vs host app). Chrome origin ≠ package name. 5 s timeout. Hidden fields.
Research biometric bypass is lab-only.

## Slide 9 — Sources (if challenged)

Official: AutofillService, autofill-services guide, CredMan provider guide, AOSP 5 s
timeout, WHATWG autocomplete, IME inline guide.  
Papers: Aonzo CCS’18, AutoSpill CODASPY’23.  
Code: Android `input-samples` AutofillFramework (`990d01c`); Bitwarden Android (`d23d1d1`).

## Backup

Full write-up: [report.md](report.md). Matrix: [compatibility-matrix.md](compatibility-matrix.md).
Comparison: [autofill-vs-credential-manager.md](autofill-vs-credential-manager.md).
