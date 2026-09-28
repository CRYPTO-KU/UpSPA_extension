# Tentative mobile development plan

**Updated:** 28 September 2026  
**Status:** Tentative capability roadmap  
**Integration branch:** `mobile-dev`  
**Architecture:** [Mobile architecture and decisions](mobile-architecture.md)

## Objective and scope

Deliver native UpSPA credential providers that preserve browser-compatible authentication and
support setup, registration, login-server secret changes, master-password changes and recoverable
account/provider management. Login servers (LSs) are the services receiving derived credentials;
storage providers (SPs) hold the distributed protocol state. Android comes first. iOS reuses the qualified shared core and workflow
contracts. Production release additionally requires protected catalog sync/recovery and operational
readiness.

The plan covers product capabilities, implementation order, dependencies and completion criteria
from foundational development through production release.

## Product capabilities

| Capability | Intended outcome |
|---|---|
| Setup and provider management | Provision an UpSPA profile, configure providers and thresholds, and expose degraded or unavailable provider state clearly |
| Registration | Derive a browser-compatible credential for the enrolled account and complete registration with explicit confirmation and recoverable pending state |
| Authentication | Verify the destination, unlock securely, obtain a matching provider quorum and deliver the exact credential through the system provider interface |
| Login-server secret update | Change a site's derived credential without confusing it with a master-password change; preserve ambiguous outcomes for recovery |
| Master-password update | Update protection across providers using versioned, staged writes and reconciliation |
| Account and identity management | Maintain protected account metadata and verified app/web aliases bound to the exact enrolled origin |
| Recovery | Survive cancellation, partial writes, lost responses, process death and device replacement according to explicit recovery guarantees |
| Cross-platform support | Reuse shared Rust protocol/workflow semantics across native Android and iOS hosts |
| Production operation | Support encrypted catalog sync, privacy controls, monitored rollout, incident response and key/certificate rotation |

## Architecture foundations

Establish a shared Rust protocol and operation engine, a versioned UniFFI boundary, and native
platform hosts. Android uses Kotlin/Compose and system Autofill/credential-provider APIs; iOS uses
a native app and AutoFill extension. Hosts provide transport, secure storage, clock, identity
evidence and protected UI while Rust owns protocol validation and workflow decisions.

Define the canonical browser-compatible encoder and compatibility profile before integration.
Keep requested destination identity separate from the stored enrolled origin used for derivation.
Never persist master passwords or reusable derived login credentials. Use encrypted metadata,
validated non-secret snapshots and separately classified recovery journals.

Build synthetic fixtures and executable security gates alongside the implementation. Cover
component permissions, backups, prohibited credential-delivery channels, sensitive-screen
protection, intent replay, transport policy, logging/persistence and secret-bearing FFI fields.
Every critical check needs valid controls and an isolated fault that proves failure detection.

## Delivery sequence

| Stage | Capability | Dependencies | Exit evidence |
|---|---|---|---|
| A — Foundation implementation and qualification (M0–M2) | Repository setup, compatibility profile, FFI/engine contracts, conformance and secure provider shell | Agreed architecture, protocol/identity requirements and development environments | Reproducible checks, generated bindings, fake native lifecycle, validated restore and gate controls |
| B — Android authentication (M3 + essential M4) | Verified destination → protected unlock → matching SP quorum → exact system fill | A; minimum verified identity and encrypted catalog; controlled HTTPS SPs | G1 on the target Android matrix with cancellation, expiry and process-death rejection |
| C — Complete recoverable workflows (M4–M5) | Setup, registration, LS-secret update, master update; durable journals and recovery UI | B; reviewed and implemented SP write/status/epoch contract | G2 with explicit pending, ambiguous, degraded and reconciled outcomes |
| D — Android controlled pilot (M6) | Selected real-app compatibility, dynamic security and operational hardening | C; independent SP infrastructure and review | G3 with versioned compatibility evidence and an approved release candidate |
| E — Native iOS qualification (M7) | Native app/extension using shared semantics | Stable G2 contracts; full feature rollout after Android pilot qualification | Swift parity and physical-device lifecycle, identity, storage and resource evidence |
| F — Production readiness (M8) | Protected sync, key bootstrap/recovery, privacy and release operations | Qualified Android/iOS paths and reviewed recovery model | G4 with monitored rollout, incident and rollback ownership |

Early Swift artifact and parity work can run during foundation development. Full iOS feature
development follows Android qualification. Minimum identity and storage work from M4 precedes
the M3 fill path; dependencies determine implementation order. M0 conformance and M1 bindings
develop together, with native parity completed once the corresponding FFI artifacts are available.

## Release criteria

| Gate | Required result |
|---|---|
| G0 — Compatibility baseline | Reviewed core/encoder/schema/wire/toolchain profile; passing TypeScript/Rust/Kotlin/Swift parity; reproducible generation and fake boundary tests. |
| G1 — Android feasibility | Generic locked suggestion → protected master-password entry → verified target → matching 2-of-3 controlled authentication → browser-compatible fill; API 26/30/34+ evidence and cancellation/lifecycle checks |
| G2 — Complete research prototype | All required operations; encrypted catalog/journal; confirmed vs ambiguous outcomes; replay/process-death tests; synthetic data only |
| G3 — Controlled Android deployment | Tested real-app matrix, dynamic security, independent SPs, provenance and independent review; approved limited-use release |
| G4 — Production and iOS | Qualified native iOS, reviewed end-to-end catalog sync/recovery, privacy/store readiness and operational drills |

## Implementation milestones

The milestones cover the complete development lifecycle from project setup to production.
Ticket IDs provide traceability when work is broken into implementation issues.

### M0 — Canonical compatibility and reproducibility

- **BASE-01–06:** Select and record authoritative repository/core/encoder revisions, define the
  compatibility profile, pin tested toolchains and lockfiles, and classify all fixture data.
- Create a deterministic synthetic compatibility corpus covering policy shapes, lengths, character
  classes, account identifiers, counters and boundary cases. Record its schema and source provenance.
- Implement and qualify the Rust encoder against canonical browser behavior. Define normalized
  policy inputs and the supported raw-policy normalization behavior explicitly.
- Build TypeScript, Rust, Kotlin and Swift corpus consumers and reproducible CI commands. Keep
  native bindings and their generator/runtime versions aligned.
- Verify deterministic regeneration in a disposable tree. A deliberately mismatched comparison
  case must be detected, and mutating an accepted case must make each consumer's suite fail.

Exit: a reproducible compatibility profile with passing conformance checks across target bindings.

### M1 — Versioned FFI and recoverable engine

- **ARCH-01:** Establish the repository layout, shared crates, native application projects,
  artifact-generation paths and dependency direction. Keep platform code outside the Rust core.
- **CORE-01–03, PORT-01:** Define versioned commands/events/effects/errors and native transport,
  storage, clock, identity and diagnostics ports. Implement deterministic lifecycle and restore
  semantics with injected fake adapters.
- **DATA-01, OBS-01:** Versioned catalog/journal schemas and field sensitivity; bounded diagnostics
  that cannot expose host tags, payloads or secrets.
- Reject invalid snapshot contents, duplicate/mismatched events and expired operations. Restore
  returns outstanding effects with stable IDs; impossible quorum terminates explicitly.
- Generate native bindings from the same runtime/generator version; verify loading from a clean
  checkout. Clear owned secret buffers on every success and failure path.

Exit: fake native hosts can start, advance, cancel and restore a single Rust-owned lifecycle.

### M2 — Secure Android provider boundary

- **AND-01–06:** Native app, Autofill service, protected authentication Activity, conservative
  classifier, additive credential-provider support and controlled fixture suite.
- **SEC-01:** Narrow exported-component and intent boundaries, target/operation/expiry binding,
  screenshot/recents protection and backup restrictions.
- Implement poison/non-credential vetoes, hidden/disabled-node rejection and split-form contexts.
  Keep the locked row generic and expensive work outside initial provider callbacks.

Exit: system-mediated synthetic fill and rejection paths work with cancellation and process
recreation across the target device/OS matrix.

### M3 — Controlled Android authentication

- **CORE-04–05, FFI-01, NET-01:** Canonical authentication/encoder integration, byte-buffer native
  boundary, HTTPS transport and matching quorum validation with early completion.
- **AUTH-01:** Bind secure UI and selected account/target to the shared operation and final fill.
- **PERF-01, TEST-01:** Measure callback/unlock/network/core/native/end-to-end costs; exercise
  0/1/2/3 replies, duplicates, mixed versions, late replies, app switching and process death.
- Require verified identity and the minimum protected catalog from M4 before derived credentials
  can be released. Benchmark the integrated build.

Exit: G1, using preprovisioned synthetic accounts and controlled providers with no secret
persistence/logging. Full in-app provisioning is delivered with the M5 workflows.

### M4 — Verified identity and encrypted catalog

- **ID-01–04:** Distinct requested/enrolled identity, exact origin handling, PSL/IDNA rules,
  installed signing evidence, strict DAL validation, verified aliases and request binding.
- **DATA-02–03:** Device-key-protected encrypted metadata/journals; versioned migrations,
  authenticated prototype import/export, rollback/key-loss rejection and backup exclusions.
- **UX-01:** Post-unlock account selection and clear verified/unverified/pending identity states.
- Test contradictory signer evidence, rotation, malicious suffixes, subdomain/port differences,
  malformed imports and native/WebView ambiguity. Manual aliases cannot release real credentials.

Exit: the correct enrolled account survives restart in encrypted storage and is offered only to a
verified destination.

### M5 — Durable mutations and distributed recovery

- **SPAPI-01–03:** Implement operation/request IDs, idempotent writes, version/epoch status queries,
  staged activation and reconciliation in the reference backend and client contract.
- **FLOW-01–05:** Setup, registration, LS-secret update, master-password update and a protected
  pending-operation/recovery inbox. Journal before writes; distinguish confirmed and ambiguous LS
  outcomes; preserve degraded replication and recovery state.
- **MODEL-01:** Executable fault scenarios for every transition, including lost replies, partial
  writes, divergent state, replay, cancellation and process death.
- Model LS and SP changes as separate durable steps. Cancellation preserves committed side
  effects and records any reconciliation still required.

Exit: G2; every required workflow has explicit success, failure, ambiguity and recovery semantics.

### M6 — Android pilot hardening

- **COMPAT-01:** Versioned app/browser/form/API/OEM/keyboard matrix with explicit unsupported cases.
- **SEC-02–03:** Runtime tests for screenshots, overlays, IPC/intent replay, backups, transport,
  logs/crashes and target switching; documented compromised-device risk policy.
- **SUPPLY-01, OPS-01:** Dependency/license inventory, SBOM and source/artifact provenance;
  independent SP deployments, outage and rotation drills.
- **REVIEW-01, RELEASE-01:** Independent security review, signed release candidate and disable/
  rollback procedure, with tracked resolution of security findings.

Exit: G3; a bounded, reviewed pilot with support and incident ownership.

### M7 — Native iOS application and extension

- **IOS-01–02:** Device/simulator artifacts, Swift bindings and native app/AutoFill extension.
- **IOS-03–05:** Keychain/App Group storage boundary, native transport, associated-domain evidence
  and metadata-only system indexing with typed propagation/reindex states.
- **IOS-06–07:** Physical-device cold launch, memory headroom, cancellation/termination, entitlement
  and identity checks; cross-platform credential parity on the same compatibility profile.
- Reuse shared workflow semantics, keep platform-specific behavior in the host, and measure
  extension resource use on physical devices.

Exit: iOS satisfies G1/G2 semantics plus device-specific qualification.

### M8 — Production sync, recovery and operations

- **SYNC-01–02:** Decide catalog-key bootstrap and implement end-to-end encrypted, versioned sync
  with conflict, rollback/replay and trust-domain separation tests.
- **REC-01:** Define device replacement, key loss and recovery guarantees, including irreversible
  loss cases and the cryptographic limits of recovery.
- **PRIV-01, OPS-02, STORE-01:** Data inventory and disclosures; monitoring, incident response,
  dependency/PSL refresh, signing/certificate rotation, staged rollout and store preparation.

Exit: G4 with reviewed recovery, qualified platforms and exercised operating procedures.

## Dependencies and infrastructure

The critical sequence is architecture and project setup → qualified contracts/parity → verified
identity and minimum catalog → Android authentication → SP mutation guarantees → complete
workflows → Android pilot → iOS and production release.

Shared contracts and local test adapters allow the Rust engine, native hosts, identity verifier
and conformance tooling to develop independently. Cross-component integration follows contract
stabilization. Backend development supplies the compatibility and recovery guarantees required by
the mobile workflows.

Required infrastructure includes:

- Android emulators and devices covering the target API and compatibility matrix.
- macOS/Xcode, signing access and physical iOS devices.
- Controlled HTTPS storage providers and synthetic accounts for integration tests.
- Independently operated providers for the controlled pilot and production.
- Security review, release signing, monitoring and operational access.

Repository workflow, AI-assisted development rules and PR evidence requirements are documented in
[CONTRIBUTING.md](../CONTRIBUTING.md) and [AI working rules](ai-working-rules.md).
