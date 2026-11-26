# Recovery scheduling and restore-equivalence qualification

## Task baseline and acceptance

- Assignment: Mobile Implementation Tasks - 3, Göktuğ's recovery scheduling slice.
- Integration base: `mobile-dev`, `dfaf1d1ef7c7c4f3193f64f6c3ea03e50d36d417`.
- Working branch: `intern/sina-recovery-scheduling`.
- Scope: extend `crates/upspa-core/tests/recovery_engine_replay.rs`, add an isolated
  fault-control runner, and document qualification and the existing FFI integration gaps.
- Dependencies: merged recovery engine and mobile FFI only; no open branch dependency.
- Acceptance: all six provider orders, bounded synthetic reply profiles, deadline ±1 ms,
  duplicate/correlation/cancellation interleavings, and snapshot restore at every schedule
  boundary agree with the uninterrupted run at the same injected time. Verify stable IDs,
  remaining work and terminal rejection. Each isolated fault must fail a named executed
  regression, with the original controls passing. Run core and security gate suites.
- No production behavior, bindings, schema, dependencies, corpus or shared build changes.

All fixtures are synthetic. This qualifies the Rust fake-host lifecycle only; it does not
qualify native loading, transport, secure persistence or device behavior. AI assisted the
test harness, fault-control runner and documentation; human review remains required.

## Bounded matrix and oracle

The existing five replay scenarios remain in place. Four new test functions extend the
same `FakeHost` and `FakeClock`; no alternative engine or native lifecycle was added.

`replay_scheduling_matrix_restores_equivalently_at_every_boundary` enumerates:

| Dimension | Cases |
|---|---|
| Providers and threshold | Three providers, threshold two; all six reply orders |
| Synthetic profiles | `AAA`, `AAB`, `ABC`, `RAA`, `ARA`, `AAR`, `ARR`, `RAR`, `RRA`, `RRR` |
| Timing | First reply at 100 ms; subsequent replies at 999, 1000 or 1001 ms; deadline 1000 ms |
| Interleavings | Plain; one duplicate after the first reply; unknown request, rebound request, wrong operation, empty cancel correlation, or empty timeout correlation before the first reply; cancellation in each of the four reply gaps |
| Restart points | Before the first step and after every step, including rejected events and terminal transitions |

`A/B/C` are accepted synthetic digests; `R` is a rejected response with synthetic
metadata. `AAA` is all matching, `AAB` has two matching plus one different, and `ABC`
is all different. The remaining profiles cover every acceptance/rejection arrangement
with accepted replies using `A`. This is a finite qualification set, not exhaustive
exploration of arbitrary provider counts, digests, clock changes or repeated crashes.

There are **1,980 schedules** (10 × 6 × 3 × 11) and **9,720 restart comparisons**.
Each row has one uninterrupted trace and a separate fresh execution for every restart
point. The prefix serializes its snapshot and drops its engine, host and fake clock;
the initial effects were already dropped. Only serialized bytes and injected time
leave that prefix. The restarted host is built exclusively from restore's returned
effects, which are then dropped too. Valid pending replies obtain IDs from that host.
Deliberately stale or malformed inbound messages are constructed as synthetic fixtures,
not from a saved pre-crash effect vector.

Assertions compare the entire snapshot, each typed advancement result and its effects,
outstanding request IDs and terminal rejection with the uninterrupted trace. Restore
must re-emit exactly the unanswered providers with the original IDs, or no effects for
a terminal snapshot. Additional checks verify that rejected events leave every snapshot
field unchanged, timeout records no late reply, and terminal effects carry the relevant
IDs. Failure effects correlate all configured requests; cancellation/timeout effects
correlate outstanding requests; completion effects correlate the two matching replies.

The independent outcome oracle is a finite fixture table: the five profiles containing
two `A` replies complete with `A`; the other five fail. Only `AA` and `RR` pairs terminate
after two replies in these fixtures. Cancellation and expiry expectations follow the
documented precedence. The tests do not call or reproduce the engine's general digest
counting or quorum-unavailability algorithm. Equivalence is between the **same schedule
at the same times**, not between different permutations: cancellation, expiry and which
providers form the first quorum can legitimately change the outcome or correlated IDs.

`replay_restore_at_deadline_matches_live_timeout_without_resending` adds **462 checks**:
60 initial, 60 one-reply and 34 pending two-reply prefixes, each resumed at 999/1000/1001 ms.
It compares restore with an uninterrupted engine receiving a valid timeout event at the
same time. Before expiry, restore returns remaining requests while the early timeout
event returns no effects. At/after expiry, both produce the same timeout state and effect,
with no provider resend. These distinct effect semantics are asserted explicitly.

## Reproducible commands and fault controls

Run from the repository root:

```bash
cargo test --locked -p upspa-core --test recovery_engine_replay
cargo test --locked -p upspa-core
python3 scripts/check_recovery_fault_controls.py
python3 tools/security-gates/run_gates.py .
python3 tools/security-gates/test_negative_fixtures.py
python3 tools/security-gates/test_positive_fixtures.py
rustfmt --edition 2021 --check crates/upspa-core/tests/recovery_engine_replay.rs
git diff --check
```

The [fault runner](../scripts/check_recovery_fault_controls.py) copies tracked workspace
sources **from the current working tree**, so local edits to the tracked replay test are
included. It uses the existing `Cargo.lock` with `--locked` and a disposable target directory.
It does not edit the real engine, Git worktrees, corpus or bindings. Cargo and the pinned
Rust toolchain must be installed, with dependencies available in cache or via the configured
registry. The runner executes the unmodified nine-test replay suite first, applies each
fault separately, restores the original source and reruns its focused control, then reruns
the complete replay suite. The temporary directory is removed on normal completion or error.

| Isolated mutation | Required executed failure | Valid control |
|---|---|---|
| In `OperationEngine::advance`, change `now_ms >= deadline` to `now_ms > deadline` | `replay_exact_deadline_rejects_completion_control` | Exact-deadline timeout and deadline-minus-one completion both pass with original code |
| Disable only restore's recorded reply/request binding rejection (`if &reply.request_id != expected`) | `replay_restore_rejects_rebound_request_control` | Valid live-generated snapshot resumes only the two remaining requests; rebound reply is rejected with `InvalidSnapshotReply` |

Each focused command is `cargo test --locked -p upspa-core --test recovery_engine_replay
<test-name> -- --exact`. A mutation counts as detected only with exit 101, the exact named
test reported `FAILED`, its failure section present, and one executed failed test with no
ignored tests. Build/setup failure, command launch failure, zero selected tests or an
unexpected passing mutant fails the runner; these are never recorded as fault detection.

## Existing FFI surface and integration gaps

This is an inspection of the merged baseline, not an implemented adapter. Sources:
[recovery engine](../crates/upspa-core/src/recovery_engine.rs),
[FFI contract](../crates/upspa-mobile-ffi/src/contract.rs),
[FFI engine](../crates/upspa-mobile-ffi/src/engine.rs),
[host ports](../crates/upspa-mobile-ffi/src/ports.rs), and
[FFI contract documentation](mobile-ffi-contract.md).

| Recovery concept | Existing mobile FFI v2 surface | Missing mapping / decision before integration |
|---|---|---|
| `OperationConfig`, `start` | `MobileCommand`, `MobileEngine::submit` | No provider set, threshold or recovery command. Only `Probe` is implemented; `DeriveCredential` and `RotateBlob` reject as unsupported. A reviewed command must delegate to the shared recovery engine. |
| Caller-supplied stable `OperationId(String)` | Engine-minted opaque `OperationId { value }` with per-instance identity; host `request_tag` is discarded | Define ownership, namespace, validation and restoration of stable IDs without reusing host text or breaking cross-instance rejection. Equal Rust type names do not establish compatible lifetimes. |
| One deterministic `RequestId` per configured `StorageProviderId` | `MobileEffect` and `deliver(operation, outcome)` correlate only the operation | No provider/request binding or exact outstanding-request set crosses FFI. `SendRequest.attempt` is not a stable request ID. Preserve duplicate, unknown and rebound rejection in a versioned adapter. |
| `SendStorageProviderRequest` and multiple outstanding effects | Reserved `EffectBody::SendRequest { endpoint, payload, attempt }`, `TransportPort::send`; `submit` returns one effect | Define provider routing, bounded concurrent execution, cancellation, and response validation. A transport success or arbitrary response bytes must not become accepted matching evidence. |
| Input `Event::StorageProviderReply` | Input `HostOutcome::RequestSucceeded` | Missing operation/request/provider evidence and typed verified matching metadata. Current probe engine does not accept a request outcome as a successful probe. Keep validation in shared Rust. |
| Input `Cancel`/`Timeout` with exact outstanding IDs | `MobileEngine::cancel(operation)`; clock checks in `submit`, `deliver`, `cancel` | No request-set cancellation or timeout event contract. The host must execute cancellation effects rather than decide workflow outcomes. Define event/error precedence and effects that must reach native tasks. |
| Output `CompleteOperation`, `FailOperation`, `CancelOperation`, `TimeoutOperation` | Output `MobileEvent` / `EventBody`; `OperationCancelled`, `OperationFailed`, typed `OperationExpired`; reserved credential/blob events | Event/effect names have different directions. Map progress, terminal outcomes, request correlations and sequence handling explicitly; a synthetic quorum completion is not `CredentialReady`. |
| Explicit `now_ms` and optional `deadline_ms` | `ClockPort::now_epoch_millis`, mandatory `Deadline { epoch_millis }` | **Boundary conflict:** recovery expires at `now >= deadline`, FFI `Deadline::is_expired_at` uses `now > deadline`. Core has no defined epoch. Maintainer decision needed for boundary, optional deadlines, clock domain and behavior across restart/clock changes; no silent conversion is implemented here. |
| Snapshot/restore returning engine plus outstanding effects | In-memory `MobileEngine` operation registry; `SecureStoragePort` byte blobs | No exported snapshot/restore API or validated recovery envelope. FFI contract version 2 does not version the core snapshot. Decide storage classification, schema, authentication, rollback and migration before persistence integration. |
| Typed `RecoveryEngineError` and `QuorumUnavailable` | `MobileError` and event reason codes; diagnostics port | Missing typed recovery-error mapping. Keep bounded codes and opaque IDs; do not echo arbitrary provider data, host tags or synthetic digest fields into logs. |

The FFI probe registry is a boundary scaffold, not a second production recovery engine.
Future integration must delegate lifecycle/quorum decisions to `OperationEngine` rather
than maintain competing state machines. Native hosts execute effects and supply evidence/time.
These mapping decisions remain open; this slice adds no FFI lifecycle or exported API.

`state_digest` remains explicitly synthetic matching metadata. Production matching needs
reviewed version/epoch/record-lineage evidence; arbitrary equal strings are not protocol
proof. `OperationSnapshot` still has **no schema version** and is not a durable cross-release
format. This task introduces neither a migration policy nor secure persistence. Preserve
requested versus enrolled identity, the canonical encoder/corpus, and byte-oriented secret
buffers with bounded ownership/erasure in any later integration.

## Local evidence and limits

Verified on 20 November 2026 in the Linux workspace with Rust/Cargo 1.96.0 and Python 3.13.13.
Baseline is the exact commit above; the changes were uncommitted during these runs.

| Executed check | Result |
|---|---|
| Existing replay suite before edits | 5 passed |
| Full `cargo test --locked -p upspa-core` after edits | 82 passed, 0 failed, 0 ignored; includes 38 recovery regressions, 9 replay tests and encoder/vector coverage; doc tests contain 0 cases |
| `python3 scripts/check_recovery_fault_controls.py` | Original replay 9/9; both named mutants failed as required; restored focused controls 1/1 each; final replay 9/9 |
| `python3 tools/security-gates/run_gates.py .` | All eight gates passed on the actual workspace |
| `python3 tools/security-gates/test_negative_fixtures.py` | All eight negative fixtures triggered only their expected gate |
| `python3 tools/security-gates/test_positive_fixtures.py` | One positive fixture passed with no findings |
| `rustfmt --edition 2021 --check crates/upspa-core/tests/recovery_engine_replay.rs` and `git diff --check` | Passed |
| Python standard-library link/syntax inspection | All seven local Markdown links resolve; fault runner parses with `ast.parse` |

During test development the first new replay run failed only its pending-prefix count
assertion (expected 468, observed 462). The enumeration was recounted as 154 prefixes ×
three times and the assertion corrected; no engine behavior was changed to obtain a pass.

Not run: Android/iOS builds, emulator/device tests, Kotlin/Swift consumers, binding generation,
live provider/database tests or remote CI. This slice changes only Rust test support and docs;
native/transport qualification belongs to separate tasks. Static gate success and fake-host
replay do not establish runtime security, production durability or native qualification.
