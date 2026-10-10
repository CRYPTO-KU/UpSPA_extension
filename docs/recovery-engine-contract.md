# Recovery Engine Snapshot and API Contract

**Status:** Current implementation contract
**Component:** `crates/upspa-core/src/recovery_engine.rs`
**Scope:** Transport-neutral mobile recovery engine
**Platform dependency:** None

## 1. Scope and architectural boundary

The recovery engine is the workflow authority for recoverable mobile operations.

It is intentionally transport-neutral and has no Android, Kotlin, networking, storage, or FFI dependency. The engine does not send network requests itself. Instead, it emits typed `Effect` values for a host to execute and consumes typed `Event` values representing the resulting observations.

The native host is responsible for:

- executing emitted effects,
- delivering provider responses back as events,
- supplying deterministic time through `now_ms`,
- persisting and restoring snapshots.

The host must not implement a second quorum or lifecycle state machine.

The engine currently models Storage Provider request/reply recovery only. Real Login Server and protocol integration remain outside this implementation.

## 2. Engine inputs

### 2.1 `OperationConfig`

An operation starts with:

- `operation_id`
- ordered `storage_providers`
- configurable `threshold`
- optional `deadline_ms`

The Storage Provider list must:

- not be empty,
- contain unique provider IDs.

The threshold must be between `1` and the number of configured providers.

Invalid configurations are rejected with typed `RecoveryEngineError` values.

### 2.2 Events

The engine currently accepts three event types:

#### `StorageProviderReply`

Contains:

- `operation_id`
- `request_id`
- `provider_id`
- `accepted`
- synthetic `state_digest`

A provider may contribute at most one accepted response event to an operation.

Unknown providers, unknown request IDs, stale request/provider bindings, duplicate replies, and operation mismatches are rejected.

#### `Cancel`

Contains:

- `operation_id`
- the currently outstanding `request_ids`

The supplied request correlation must exactly match the engine's outstanding requests.

#### `Timeout`

Contains:

- `operation_id`
- the currently outstanding `request_ids`

The supplied request correlation must exactly match the engine's outstanding requests.

### 2.3 Injected time

`OperationEngine::advance` takes `now_ms` explicitly:

```rust
engine.advance(event, now_ms)
```

The engine does not read a system clock.

This keeps tests and replay deterministic and makes the host responsible only for supplying the current time value.

The deadline is checked before any state-advancing event is processed.

If:

```text
now_ms >= deadline_ms
```

the operation expires before the supplied event is applied.

Therefore, a late provider reply cannot complete an already expired operation.

## 3. Engine outputs

### 3.1 `OperationStatus`

The current statuses are:

```text
Pending
Completed
Failed
Cancelled
TimedOut
```

`Completed` records:

- `committed_digest`
- `matching_replies`

`Failed` currently has one typed failure:

```text
OperationFailure::QuorumUnavailable
```

### 3.2 Effects

The engine emits typed effects rather than performing host operations directly.

Current effects are:

```text
SendStorageProviderRequest
CompleteOperation
FailOperation
CancelOperation
TimeoutOperation
```

`SendStorageProviderRequest` contains:

- operation ID,
- one stable request ID,
- provider ID.

Operation-wide transition effects also carry the request IDs relevant to that transition.

For completion, the correlated IDs are the matching provider requests that produced the quorum.

For cancellation and timeout, the correlated IDs are the requests still outstanding at the transition.

`FailOperation` carries the operation's request correlation together with the typed failure reason.

### 3.3 `AdvanceResult`

Every successful advancement returns:

```rust
AdvanceResult {
    status,
    effects,
}
```

An empty `effects` vector means that the state transition does not require a new host action.

## 4. Stable identifiers

### 4.1 Operation ID

Every operation is identified by a stable `OperationId`.

Events belonging to a different operation are rejected.

### 4.2 Request ID

Each provider receives one deterministic request ID.

The current request-ID rule is:

```text
{operation_id}:{provider_id}:request-v1
```

For example:

```text
op-1:sp-1:request-v1
op-1:sp-2:request-v1
op-1:sp-3:request-v1
```

The same IDs survive snapshot serialization, process death, deserialization, and restoration.

Restoration never generates replacement request IDs for an existing operation.

These identifiers are correlation metadata. They are not credentials or protocol secrets.

## 5. Lifecycle

### 5.1 Start

`OperationEngine::start(config)` validates the configuration and returns:

```text
OperationEngine
+
one SendStorageProviderRequest effect per configured provider
```

The initial status is:

```text
Pending
```

### 5.2 Provider replies

Provider replies may arrive in any order as long as:

- the operation ID matches,
- the provider is configured,
- the request ID is known,
- the request ID belongs to that provider,
- the provider has not already replied,
- the operation is still pending,
- the deadline has not expired.

### 5.3 Completion

A successful completion requires at least `threshold` accepted replies carrying the same synthetic state digest.

For the standard 2-of-3 case:

```text
SP1 -> accepted / digest A
SP2 -> accepted / digest A
SP3 -> not required
```

The operation completes immediately after the second matching response.

It does not wait for the third provider.

The completion effect is correlated only with the request IDs that formed the matching quorum.

### 5.4 Cancellation

Cancellation is permitted only while the operation is pending.

The cancellation event must identify the complete set of currently outstanding request IDs.

If correlation is valid and the deadline has not expired, the operation becomes:

```text
Cancelled
```

and emits a correlated `CancelOperation` effect.

### 5.5 Timeout

The deadline is enforced before processing every state-advancing event.

If the deadline is reached or exceeded, the operation becomes:

```text
TimedOut
```

and emits a `TimeoutOperation` effect correlated with the requests still outstanding.

An explicit timeout event before the deadline does not terminate the operation.

### 5.6 Quorum unavailable

The engine detects when a matching quorum has become mathematically impossible.

Let:

```text
best_matching = largest number of accepted replies sharing one digest
remaining = providers that have not replied
```

A quorum is impossible when:

```text
best_matching + remaining < threshold
```

For example, with threshold `2`:

```text
SP1 -> rejected
SP2 -> rejected
SP3 -> outstanding
```

At this point the maximum possible successful matching count is `1`, so the engine does not remain indefinitely pending.

It transitions to:

```text
Failed {
    reason: QuorumUnavailable
}
```

The 0-of-3 and 1-of-3 failure paths therefore terminate once no legal sequence of remaining replies can satisfy the threshold.

## 6. Terminal behavior

The terminal states are:

```text
Completed
Failed
Cancelled
TimedOut
```

Once an operation reaches a terminal state, later calls to `advance` are rejected with a typed error.

Examples include:

```text
AlreadyCompleted
AlreadyFailed
AlreadyCancelled
AlreadyTimedOut
```

The engine does not accept later provider replies after a terminal transition.

This also prevents replies from being recorded after an operation has already reached its threshold.

## 7. Snapshot contents

`OperationEngine::snapshot()` produces `OperationSnapshot`.

The current snapshot contains recovery metadata only:

- `operation_id`
- `storage_providers`
- `threshold`
- `deadline_ms`
- `status`
- deterministic provider-to-request mapping in `pending_requests`
- recorded provider replies

Despite the current field name `pending_requests`, the map is the stable request mapping for all configured providers. Replies determine which requests are still outstanding.

A provider reply snapshot entry contains:

- `request_id`
- `accepted`
- synthetic `state_digest`

The snapshot type does not contain:

- master passwords,
- reusable derived credentials,
- cryptographic secret buffers,
- raw protocol request payloads,
- raw protocol response payloads.

`state_digest` is currently synthetic matching metadata used by this recovery-engine slice. Production derivation and validation of matching evidence is deferred to protocol integration.

## 8. Snapshot invariants

A snapshot is treated as untrusted input during restoration.

`OperationEngine::restore` validates the snapshot before accepting it.

### 8.1 Provider membership

The provider keys in `pending_requests` must exactly match the configured Storage Provider set.

A snapshot may not:

- omit a configured provider,
- insert an unknown provider.

### 8.2 Deterministic request IDs

Each provider must retain exactly the deterministic request ID derived from:

```text
operation ID + provider ID
```

For example, rebinding `sp-1` to `sp-2`'s request ID is rejected.

### 8.3 Reply membership and binding

Every recorded reply must:

- belong to a configured provider,
- contain that provider's expected request ID.

Unknown-provider replies and request-ID rebinding are rejected.

### 8.4 Threshold

The restored threshold must still satisfy the normal configuration rules.

An invalid threshold is rejected before restoration.

### 8.5 Status consistency

The stored status must agree with the recorded replies and threshold.

Examples of rejected snapshots include:

- `Pending` even though a quorum is already recorded,
- `Pending` even though quorum is already mathematically impossible,
- `Completed` without enough matching replies,
- `Completed` with a mismatched digest/count,
- a completed threshold-2 snapshot containing a third post-terminal matching reply,
- a forged failure state when quorum is still possible,
- terminal states inconsistent with the recorded reply state.

The validator therefore rejects states that could not have been produced by the normal engine lifecycle.

## 9. Restore semantics

Restoration uses:

```rust
OperationEngine::restore(snapshot, now_ms)
```

The restore path performs these steps:

```text
1. Validate configuration
2. Validate all snapshot invariants
3. Reconstruct the engine
4. Apply the injected current time
5. Re-emit only effects required to resume
```

### 9.1 Pending and still valid

For a valid pending operation that has not expired, restore re-emits:

```text
SendStorageProviderRequest
```

only for providers that have not yet produced a recorded reply.

The re-emitted effects retain their original stable request IDs.

### 9.2 Already answered providers

A provider with a reply stored in the snapshot is not re-emitted.

### 9.3 Expired while the process was dead

If the restored operation is pending but:

```text
now_ms >= deadline_ms
```

restore does not resend provider requests.

Instead it restores directly as:

```text
TimedOut
```

and returns the corresponding timeout effect for the still-outstanding requests.

### 9.4 Terminal snapshots

A valid terminal snapshot does not resume provider requests.

No new `SendStorageProviderRequest` effects are emitted for a terminal operation.

## 10. Process-death recovery

The recovery contract does not depend on retaining the original in-memory engine or its original effects.

A real recovery flow is:

```text
running engine
     |
     v
snapshot()
     |
     v
serialize
     |
     X process death
     |
     v
deserialize
     |
     v
restore(snapshot, now_ms)
     |
     v
restored engine + outstanding effects
```

The pre-death effects are not required after restore.

The existing regression test explicitly drops both the original engine and original effects before restoring.

## 11. Runnable synthetic snapshot/replay example

A focused process-death recovery example is already executable as a Rust test.

From the repository root:

```bash
cargo test --locked -p upspa-core \
  --test recovery_engine_tests \
  restore_continues_pending_operation_after_process_death \
  -- --nocapture
```

The tested sequence is:

```text
1. Start a synthetic 2-of-3 operation.
2. SP1 returns accepted / digest-a.
3. Snapshot the pending operation.
4. Serialize the snapshot with serde_json.
5. Drop the original engine.
6. Drop the original effects.
7. Deserialize only the saved snapshot.
8. Restore the engine.
9. Verify SP2 and SP3 are re-emitted with their original request IDs.
10. Return SP2 accepted / digest-a.
11. Verify the restored operation completes 2-of-3.
```

A second runnable restore test verifies process death across a deadline:

```bash
cargo test --locked -p upspa-core \
  --test recovery_engine_tests \
  restore_after_deadline_times_out_without_reemitting_requests \
  -- --nocapture
```

That test verifies that an expired operation becomes `TimedOut` during restore and does not resend Storage Provider requests.

To run the full recovery-engine regression suite:

```bash
cargo test --locked -p upspa-core --test recovery_engine_tests
```

## 12. Security properties and negative controls

The current test suite includes negative controls for:

- invalid thresholds,
- duplicate provider configuration,
- unknown providers,
- unknown requests,
- stale request/provider bindings,
- operation mismatches,
- duplicate provider replies,
- forged cancellation correlation,
- forged timeout correlation,
- malformed snapshot provider membership,
- deterministic request-ID rebinding,
- unknown provider replies in snapshots,
- reply/request mismatches,
- forged completed status,
- post-terminal replies embedded in snapshots,
- late replies after a deadline.

These checks are fail-closed.

All values used by the recovery-engine tests are synthetic.

## 13. Snapshot format and version limitations

The current `OperationSnapshot` derives Serde serialization and can be serialized with `serde_json`.

However, the snapshot format currently has **no explicit schema/version field**.

Therefore:

- the current serialized representation must not yet be treated as a permanent storage compatibility format,
- enum or field changes may be breaking changes for previously persisted snapshots,
- there is currently no snapshot migration mechanism,
- forward/backward compatibility across incompatible application releases is not guaranteed.

The stable `request-v1` suffix versions the current deterministic request-ID construction, but it does not version the entire snapshot schema.

A production persistence layer should introduce an explicitly reviewed snapshot format/version and migration policy before long-lived persisted snapshots are relied upon across application upgrades.

## 14. Current limitations and deferred integration

This engine is currently a deterministic recovery core, not a complete mobile protocol implementation.

Deferred work includes:

- mobile FFI integration,
- Android/iOS host integration,
- secure persistent snapshot storage,
- real Storage Provider transport adapters,
- Login Server workflow integration,
- production definition and validation of matching state evidence,
- broader reconciliation workflows,
- explicit persistent snapshot schema versioning.

These integrations must consume this engine's workflow decisions rather than duplicating quorum, deadline, or recovery logic in the native host.
