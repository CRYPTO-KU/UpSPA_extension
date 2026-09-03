# Universal Autofill Engine — Architecture Proposal

**Objective:** Design a high performance, universal autofill engine for the browser extension that works across the modern web **without site specific rules**. There are no `if (domain === ...)` blocks anywhere in the design: every site is handled by the same heuristic detection pipeline, the same lifecycle model, and the same traversal strategy. This document identifies the major challenges of universal autofill, proposes the most reliable detection strategy, and describes a scalable architecture for handling dynamic single page applications and encapsulated DOM.

## 1. Major Challenges of Universal Autofill

A universal engine must solve four fundamentally different problems at once:

*   **Ambiguous markup.** There is no reliable, universally adopted standard for labeling login fields. Some sites use spec compliant `autocomplete` attributes; most rely on arbitrary `name`/`id`/`class` values, localized labels, or no semantic markup at all. Text labels are especially treacherous: they change per language, so any strategy built primarily on visible text fails outside English language sites.
*   **Dynamic rendering.** Modern SPAs (React, Vue, Angular) build and destroy forms continuously — login modals mount on click, views swap via client side routing, and fields appear hundreds of milliseconds after the page "loads". A one shot scan at page load misses most real world login surfaces. The engine must treat the DOM as a stream of changes, not a static document.
*   **Encapsulation boundaries.** Web Components hide fields behind Shadow DOM; auth widgets live inside iframes. A naive `document.querySelectorAll` sees none of this. The engine needs a deliberate traversal strategy for every reachable DOM boundary, and a clear position on the boundaries that are unreachable by design (closed shadow roots, cross origin frames).
*   **Framework controlled inputs.** Frameworks track input state internally, so a fill is only "real" if the framework observes it the same way it observes user typing. Writing a value into the DOM is not enough; the fill path must dispatch the events frameworks listen for, or submitted forms will carry empty state despite visually filled fields.

Any architecture that solves these with per site patches accumulates unbounded maintenance cost. The proposal below solves them structurally.

## 2. Recommended Detection Strategy (Topology & Heuristics)

Detection uses a **layered confidence model**: the cheapest and most authoritative signals are evaluated first, and expensive analysis runs only when needed.

*   **Tier 1 — Authoritative signals.** Fields carrying spec defined `autocomplete` tokens (`username`, `current-password`, `new-password`, `one-time-code`) are classified immediately with full confidence, short circuiting the rest of the pipeline. When sites follow the standard, we trust the standard.
*   **Tier 2 — Weighted attribute heuristics.** For unmarked fields, the engine builds a corpus from the field's attributes and associated label text, then scores it against weighted patterns per role (username, email, password variants, OTP). Crucially, the model includes **negative weights for "poison" terms** (search, captcha, coupon, payment fields), which is what keeps a universal regex approach precise: it is far more common to mistakenly grab a search box than to miss a password field. A field must clear a minimum score to classify at all.
*   **Tier 3 — Structural topology (the core idea).** Form *intent* is derived from the relational structure of password fields rather than from button text or headings, because structure is language independent:
    *   `1 password + 1 identifier` → **Login**
    *   `2 adjacent passwords, no current password marker` → **Register / Reset**
    *   `current password + new password` → **Update**

    Visible text (button labels, headings) is used only as a tie breaker, never as the primary signal. The same relational reasoning resolves confirm password fields: an unmarked second password adjacent to a new password field is a confirmation, not an unrelated credential — a classification that attribute only approaches consistently get wrong.

Every classification carries a **confidence score**, and the engine exposes it rather than hiding it. This lets the consuming layer make policy decisions (autofill vs. suggest only) without the detection engine embedding product policy.

**Why this strategy:** it degrades gracefully. Spec compliant sites are handled perfectly by Tier 1; the long tail of arbitrary markup is handled by Tiers 2–3; and because topology dominates text, the same engine works on a Japanese banking portal and an English SaaS login without a single localized rule.

## 3. Proposed Scalable Architecture (SPA Handling)

The engine is organized as four small, single responsibility components:

*   **Root Registry** — the catalog of every scannable DOM root (main document, discovered shadow roots, same origin iframe documents).
*   **Heuristic Engine** — the pure classification logic from Section 2; no DOM lifecycle concerns.
*   **Mutation Lifecycle** — decides *when* to rescan.
*   **Event Interceptor** — decides *when the user is interacting* (focus, input, submission) and surfaces those moments to the consumer.

The lifecycle design is what makes the architecture scale on mutation heavy SPAs:

*   **Observe cheaply, scan lazily.** Every registered root is watched by a `MutationObserver`, but observer callbacks perform **no DOM reads** — they only filter out irrelevant records (using an attribute filter so style churn and framework noise are discarded at the browser level) and mark the mutated root dirty.
*   **Debounce with a ceiling.** Mutation bursts are coalesced by a trailing edge debounce so that a React render storm produces one rescan, not hundreds. A maximum wait ceiling guarantees that even a page that mutates continuously still gets scanned at a bounded interval.
*   **Scan during idle time.** The actual rescan is scheduled through idle callbacks, keeping heavy DOM walks off the critical path of user interaction and animation.
*   **Rescan only what changed.** Because observers record *which* root mutated, a rescan rewalks only dirty roots; results for untouched roots are reused. On component heavy pages this turns most rescans into small, localized walks instead of full page traversals.
*   **Emit only real changes.** Scan results are diffed structurally before notifying the consumer, so UI layers never re-render in response to no op rescans.

This gives the engine a simple, provable performance contract: user interaction is never blocked by detection work, and detection cost is proportional to how much of the page actually changed — not to how often the page mutates.

## 4. Piercing Encapsulation (Shadow DOM & Iframes)

The page is treated as a **forest of roots** rather than a single tree.

*   **Traversal.** Each root is walked iteratively with an explicit stack (no recursion limits, and trivially interruptible for time slicing later if needed). During the walk, newly encountered open shadow roots and same origin iframe documents are registered as first class roots, each receiving its own observer and listeners — so a dynamically mounted web component is discovered, scanned, and monitored within the same pass.
*   **Same origin iframes** are entered directly and scanned like any other root, with a bounded nesting depth as a safety valve.
*   **Cross origin iframes** cannot and should not be entered — the Same Origin Policy makes this fail closed, by design. They are covered instead by declaring `all_frames: true` in the manifest, which makes the browser inject an independent engine instance into every frame, each operating on its own document. Coverage of cross origin login widgets (hosted auth providers) is therefore a deployment configuration, not an engine feature.
*   **Closed shadow roots** are unreachable by any content script. This is an accepted platform boundary, not a gap we attempt to hack around; it is rare in practice for login forms.
*   **Event recovery.** Events crossing shadow boundaries are re targeted by the browser; the interceptor recovers the true origin field via the event's composed path, so focus and submission detection work identically inside and outside web components.

Root lifecycles are managed explicitly: when a shadow host is detached or an iframe is torn down, its observers and listeners are released, so long lived SPA sessions do not accumulate detection machinery for DOM that no longer exists.

## 5. Scope & Security Boundaries

This engine deliberately owns **detection and fill mechanics only**. Two security critical decisions are explicitly out of its scope and must live in the credential orchestration layer that consumes its output:

*   **Origin binding.** The engine never chooses *which* credential to offer. The caller must match the stored credential's origin against the page's actual origin before any fill — DOM heuristics provide zero protection against a pixel perfect phishing page.
*   **Frame trust.** Whether autofill should be offered inside a sub frame at all is a policy decision, since a hostile top level page can embed a look alike iframe.

Keeping these concerns above the engine keeps the detection layer simple and universally reusable, while making the security boundary explicit rather than accidental.

## Summary

The proposal avoids overengineering by betting on three durable ideas: **standards first, structure over text, and mutation driven lifecycle**. Authoritative markup is trusted when present; language independent password topology classifies everything else; and a debounced, idle scheduled, dirty root scoped rescan pipeline keeps the engine correct on dynamic SPAs without ever blocking the user. Everything site specific is excluded by construction, so maintenance cost does not grow with the number of supported sites — which is the defining requirement of a *universal* autofill engine.
