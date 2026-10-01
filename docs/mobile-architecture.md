# Mobile architecture and decision baseline

**Updated:** 28 September 2026  
**Status:** Target architecture and decision baseline  
**Roadmap:** [Mobile development plan](mobile-development-plan.md)

This document defines the mobile architecture, security invariants and decisions needed for
implementation. The [roadmap](mobile-development-plan.md) organizes delivery from foundations to
release. Together they form the maintained mobile planning reference; historical research and
assignment documents provide supporting context.

## Product boundary

Build a native system credential provider for UpSPA, Android first and iOS afterward. Support
setup/provisioning, registration, authentication, login-server (LS) secret update, master-password
update, account selection and storage-provider (SP) management. Reuse browser-compatible protocol
and password encoding semantics from this repository.

Use system Autofill/credential-provider APIs. Do not implement credential delivery through an
embedded browser, Accessibility automation, overlays, a custom keyboard or clipboard transport.
Compatibility claims cover tested apps/forms and platform versions; they are never universal.

## Layers and contracts

| Layer | Responsibility | Boundary |
|---|---|---|
| Rust core and encoder | Canonical cryptographic operations, validation and password encoding | No platform UI or native networking |
| Rust operation engine | Commands, transitions, quorum, deadlines, IDs, cancellation and recovery | Emits effects; consumes typed events; one workflow authority |
| UniFFI boundary | Versioned commands/events/effects/errors and native artifacts | Byte-oriented secrets; explicit lifetimes and compatibility |
| Native host | OS provider callbacks, secure UI, transport, storage, clock and platform identity evidence | Executes effects; does not duplicate protocol or quorum decisions |
| Identity/catalog | Verified destination-to-enrolled-account mapping and protected local metadata | Separate requested identity from derivation identity |
| SP services | Protocol responses and durable/versioned write contracts | Untrusted responses require validation; failure/reconciliation explicit |

Dependency direction is native host → FFI → shared engine/core. Host ports are injectable for
repeatable tests. Use fake adapters during boundary development; the integrated application uses
one shared engine for workflow decisions.

## Platform decisions

| Topic | Direction |
|---|---|
| Android | Native Kotlin/Compose; minimum API 26; Autofill first; API 34+ credential-provider support is additive |
| iOS | Native SwiftUI containing app and native AutoFill extension; proposed minimum iOS 17, subject to qualification |
| Shared code | Rust via UniFFI; no shared UI runtime in the iOS extension |
| Transport | Native OkHttp/URLSession adapters; HTTPS; platform trust store initially |
| Locked UI | One generic UpSPA entry; reveal account identity only after protected unlock |
| Biometrics | Deferred until the exact persisted equivalent secret and changed threat model are reviewed |
| Root/jailbreak | Risk communication, no default universal hard block or guarantee of protection |

Keep initial provider callbacks local and bounded; no network or derivation on that path. Start
expensive work only in the protected operation flow. Revalidate target, operation, account and
expiry before releasing the result. App switching, cancellation, backgrounding and process death
must not cause stale credential delivery.

Protect sensitive screens and recents. Use explicit intents and narrow component permissions.
Prefer immutable PendingIntents where compatible with the OS contract; Autofill's result-bearing
mutable intent is a documented exception and still requires one-shot/replay protection. Follow
[the executable gates](../tools/security-gates/README.md) and test the actual runtime behavior.
Static intent checks are supplemented by runtime replay and target-binding tests.

## Compatibility is a release boundary

The canonical encoder is
[`packages/extension/src/shared/passwordPolicy.ts`](../packages/extension/src/shared/passwordPolicy.ts),
identified by the committed [compatibility manifest](../test-vectors/compatibility-profile-v1/manifest.json).
Use the corresponding Rust encoder; do not import the different User Study encoder or rewrite it
in Kotlin/Swift. Fully normalized policies are the current Rust parity contract. Raw or partial
policy normalization needs an explicit compatibility decision and shared tests.

The release profile must eventually identify reviewed core/encoder commits, vector schema,
account/catalog and wire versions, lockfiles and tested Rust/Android/Swift toolchains. Browser/TS,
Rust, Kotlin and Swift must consume the same accepted vectors. Missing binding tests are an open
qualification gap, not an implicit pass. Profile changes need migration and compatibility review.

## Identity and derivation

Keep three concepts distinct: the identity reported by the current request, the verified service
that matches it, and the exact stored origin used during enrollment. Profile v1 derives the LS
identifier from `enrolledOrigin + "|" + accountId`; never replace the enrolled origin with a
package/bundle ID or silently drop a scheme, port or subdomain. The unescaped separator requires
mobile enrollment to reject `|` in new account IDs. Existing identity bytes remain unchanged;
legacy incompatible IDs require an explicit migration/recovery decision.

Android aliases require exact package/signing-certificate evidence and verified Digital Asset
Links (DAL). Handle multiple signers, rotation lineage, contradictory representations and malformed
certificates explicitly. Production evidence must come from validated platform/network sources;
a pure verifier with injected evidence does not prove a deployed alias is verified.

iOS aliases require the corresponding associated-domain/app identity evidence. Treat stale,
missing or temporarily unavailable evidence as a typed unverified outcome, not a silent allow.
Use real origin parsing, reviewed IDNA normalization and a versioned Public Suffix List where
matching needs them. No substring, naive suffix or branding match may release a credential.
A browser package alone does not establish a website identity. Ambiguous native/WebView requests
remain unverified until the exact destination and association can be established.

## Secrets, metadata and persistence

Never persist or log the master password, reusable derived LS credential, password-state key,
plaintext protocol key material or raw secret intermediates in the mobile client. Use byte buffers/secret wrappers at
mobile boundaries, minimize copies, and erase owned buffers on every exit. Native clearing is
best effort; managed and OS copies prevent a claim of perfect erasure.

Encrypt account/catalog metadata with a random data-encryption key protected by device key
storage. Bind schema/profile versions using authenticated encryption; exclude sensitive data and
keys from backup until a recovery design explicitly permits it. Handle key loss, migration,
rollback, malformed imports and schema skew as typed failures. A system credential index is a
cache, never the authoritative catalog or a secret store.

Distinguish **engine snapshots** from **durable mutation journals**:

- A snapshot contains only validated non-secret state/IDs/status, never raw protocol payloads.
- A later mutation journal may contain a separately reviewed, encrypted prepared SP payload needed
  for forward recovery. Each field needs sensitivity/lifetime classification; encryption does not
  permit storing master passwords or reusable LS credentials.
- Recovery requiring fresh secrets must request protected re-unlock rather than caching a password.

Prototype import/export must be authenticated and encrypted. Production end-to-end encrypted
catalog sync, key bootstrap and device replacement remain separate decisions and release gates.

## Quorum and recovery semantics

Count at most one valid response per configured SP. Matching quorum includes operation/request
correlation and the required version/epoch/record lineage; any two arbitrary responses are not
a valid 2-of-3 result. Stop waiting for stragglers when a phase permits threshold completion.
Enforce request/phase/operation deadlines before state advancement, not only on timeout callbacks.

Restore must validate all snapshot invariants and return enough outstanding effects to resume
without retaining the pre-crash engine or effects. Duplicate, unknown, expired or mismatched events
must not revive terminal operations. Impossible matching quorum is a terminal classified failure.

Authentication is read-only. Mutations across an LS and SPs are not an atomic transaction:

- Journal before the first irreversible side effect.
- Registration and LS-secret updates require explicit generic-app confirmation or a cooperating
  LS status/idempotency contract. A save callback is only a hint; lost outcomes remain ambiguous.
- Setup and master updates need durable operation IDs, idempotent writes, epoch/status queries,
  staged activation and reconciliation before success/durability claims are supported.
- In the planned 2-of-3 write model, fewer than two matching writes are not committed; two are
  committed but degraded; three are fully replicated. Conflicting lineages cannot be combined.
  These are target semantics, contingent on the reviewed server contract and failure tests.
- Cancellation stops further unsafe work; it cannot undo an external side effect already applied.
  Preserve unresolved outcomes for recovery rather than inventing rollback or generic success.

## Network, diagnostics and release boundaries

Use response-size limits, strict decoding, bounded concurrency, cancellation and timeouts.
Do not add live transport/permissions to a fake-only milestone. When networking is authorized,
update the network gate policy with scoped justification and tests; never bypass the gate.
The current mobile gate disallows cleartext, including debug shortcuts. Initial TLS uses platform
trust; certificate pinning needs a separate rotation/recovery design before introduction.

Diagnostics contain bounded event categories, timings and opaque local IDs. Exclude host-provided
free-form request tags, bodies, credential values, user identifiers and cross-origin tracking IDs.
Test both successful and early-rejection paths.

Static gates are regression aids. Controlled deployment also requires dynamic platform checks,
versioned app/device compatibility, genuinely independent SP operations, dependency/provenance
checks and independent security review. Same-machine SPs are synthetic development infrastructure,
not evidence that operational threshold trust is independent.

## Decisions to close before dependent work

| Decision | Needed before | Evidence required |
|---|---|---|
| Full compatibility pins and policy-normalization boundary | Real mobile authentication | Profile, cross-language vectors, reproducible builds |
| SP idempotency/epochs/status and reconciliation | Durable mutating workflows | Wire contract, server/client tests, fault campaign |
| Catalog/journal field classification and migration | Production persistence | Threat review, schema, encryption/rollback tests |
| iOS floor and extension resource budget | iOS qualification | Supported-toolchain checks and physical-device measurements |
| Catalog sync key bootstrap and device-loss recovery | Production sync | Reviewed trust/recovery model and adversarial tests |
| Biometric unlocking | Any biometric implementation | Exact secret inventory and changed threat model |
| Pinning, if proposed | Any pinned transport | Rotation, monitoring and recovery ownership |

Record resolutions with rationale, alternatives, compatibility impact and verification evidence.
