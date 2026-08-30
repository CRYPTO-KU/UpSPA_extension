# FINDINGS: Protocol Workflows, Distributed Consistency, and Recovery

Track: UpSPA mobile architecture research  
Owner: Göktuğ Sina Bekçioğulları  
Status: Initial working draft

---

## 1. Main Claim

With an unmodified Login Server, UpSPA cannot make Login Server and Storage Provider operations fully atomic.

Therefore, the mobile client should treat protocol operations as recoverable workflows rather than single atomic transactions.

The recommended design is based on:

- durable pending-operation journal,
- idempotency keys,
- staged writes,
- threshold-based success rules,
- forward recovery,
- Storage Provider reconciliation,
- and explicit handling of ambiguous states.

The preferred approach is not full rollback. The preferred approach is to make every operation recoverable, retryable, and classifiable after process death.

---

## 2. System Model

This research models the UpSPA mobile client as interacting with:

- one Login Server,
- three Storage Providers,
- a two-of-three Storage Provider threshold,
- local durable client state,
- and mobile lifecycle events such as cancellation, app suspension, and process death.

The Login Server is treated as unmodified unless explicitly stated otherwise.

---

## 3. Failure Model

The failure model includes:

- Storage Provider success,
- Storage Provider failure,
- Storage Provider timeout,
- Login Server success,
- Login Server failure,
- Login Server timeout,
- process death before Login Server submission,
- process death after Login Server submission,
- process death before Storage Provider commit,
- process death after partial Storage Provider commit,
- partial master-password update,
- divergent `cid` or version values across Storage Providers.

---

## 4. Pending Operation Specification

A pending operation should include at least:

- operation ID,
- operation type,
- Login Server identity,
- account ID or service identity,
- target Storage Providers,
- threshold,
- current state,
- Login Server submission status,
- Storage Provider write status,
- version or `cid` information,
- retry count,
- recovery decision.

Pending operations must survive process death.

---

## 5. Commit Rules

The mobile client should treat Storage Provider writes as staged until the required threshold is reached.

| SP successes | Meaning | Client behavior |
|---:|---|---|
| 0/3 | not durable | keep pending and retry |
| 1/3 | not durable | keep pending and retry |
| 2/3 | threshold committed but degraded | allow recovery, reconcile missing SP |
| 3/3 | fully replicated | clear pending operation |

---

## 6. What Cannot Be Atomic with an Unmodified Login Server

With an unmodified Login Server, the client cannot guarantee one atomic transaction across:

- Login Server registration,
- Login Server password update,
- Storage Provider writes,
- and local mobile client state.

The client also cannot always distinguish between:

- a Login Server request that failed before being applied,
- a Login Server request that succeeded but the response was lost,
- and a Login Server request that timed out while still being processed.

Therefore, some states must be treated as ambiguous unless the Login Server exposes a status or idempotency API.

---

## 7. Alternatives Considered

### Alternative A: Client-only recovery

The Login Server remains unmodified. The client uses durable pending operations, idempotency keys, staged writes, threshold rules, and reconciliation.

This is the preferred short-term approach because it can be implemented mostly on the client side.

### Alternative B: Cooperating Login Server and Storage Provider APIs

The Login Server and Storage Providers expose new APIs for operation IDs, status queries, prepare/commit, and idempotent confirmation.

This provides stronger consistency but requires protocol/API changes.

### Rejected Alternative: Full rollback or distributed transaction everywhere

This is rejected because an unmodified Login Server cannot participate in a real distributed transaction or two-phase commit protocol.

---

## 8. Experiment: Failure Simulator

The executable evidence artifact is:

`code-protocol-sim/simulator.py`

The simulator reads:

`code-protocol-sim/scenarios.json`

and writes:

`code-protocol-sim/results.json`

The experiment was run successfully and evaluated 12 scenarios.

Measured result summary:

| Scenario | Operation | LS result | SP successes | Threshold met? | Client result |
|---|---|---|---:|---|---|
| `registration_0_of_3_sp_success` | registration | success | 0 | False | `not_durable` |
| `registration_1_of_3_sp_success` | registration | success | 1 | False | `not_durable` |
| `registration_2_of_3_sp_success` | registration | success | 2 | True | `committed_but_degraded` |
| `registration_3_of_3_sp_success` | registration | success | 3 | True | `fully_committed` |
| `process_death_before_ls_submission` | registration | none | 0 | False | `safe_to_cancel_or_restart` |
| `process_death_after_ls_submission_timeout` | registration | timeout | unknown | unknown | `ambiguous` |
| `process_death_before_sp_commit` | registration | success | 0 | False | `ls_changed_but_sp_not_durable` |
| `process_death_after_partial_sp_commit` | registration | success | 1 | False | `not_durable` |
| `partial_master_password_update_2_of_3` | master_password_update | success | 2 | True | `committed_but_version_divergent` |
| `partial_master_password_update_1_of_3` | master_password_update | success | 1 | False | `unsafe_partial_update` |
| `login_server_timeout_after_submission` | secret_update | timeout | unknown | unknown | `ambiguous` |
| `divergent_cid_values` | reconciliation | success | 3 | True | `committed_but_divergent` |

### Main findings from the simulator

1. `0/3` and `1/3` Storage Provider success are not durable. The client should keep the operation pending and retry Storage Provider writes.

2. `2/3` Storage Provider success reaches the threshold. The operation can be treated as committed, but degraded. The missing Storage Provider should be reconciled later.

3. `3/3` Storage Provider success is fully committed. The pending operation can be cleared.

4. Process death before Login Server submission is safe to cancel or restart because no external Login Server side effect has happened yet.

5. Process death or timeout after Login Server submission is ambiguous. With an unmodified Login Server, the client cannot know whether the Login Server applied the operation unless there is a status or idempotency API.

6. Partial master-password updates are more dangerous than ordinary registration partial writes because they can create version divergence across Storage Providers.

7. Successful responses from all Storage Providers are not sufficient if returned `cid` or version values diverge. The client must compare versions or `cid` values and reconcile divergent Storage Provider state.

---

## 9. Risks and Open Problems

| ID | Risk | Severity | Notes |
|---|---|---|---|
| RISK-1 | Login Server success cannot always be observed after timeout | high | Requires idempotency/status API or manual confirmation |
| RISK-2 | Partial Storage Provider writes create divergent state | high | Requires versioning and reconciliation |
| RISK-3 | Process death can leave ambiguous pending operations | high | Requires durable journal |
| RISK-4 | Master-password update may partially apply | high | Requires versioned state and safe recovery |
| RISK-5 | User may see success before durable threshold is reached | medium | UI must wait for threshold or clearly show pending state |

Open problems:

- Exact current UpSPA operation ordering must be inspected in source code.
- Current Storage Provider API support for idempotency must be verified.
- Current Login Server API support for registration/status confirmation must be verified.
- The simulator assumptions must be checked against the current implementation.

---

## 10. Recommendation

For the mobile architecture, UpSPA should treat protocol operations as recoverable workflows rather than atomic transactions.

Recommended design:

1. Write a durable pending-operation record before external side effects.
2. Use stable operation IDs for retry and idempotency.
3. Treat 2/3 Storage Provider success as committed but degraded.
4. Reconcile missing Storage Provider writes in the background.
5. Treat Login Server timeout after submission as ambiguous unless a status API exists.
6. Avoid promising full rollback with an unmodified Login Server.
7. Separate client-only improvements from changes requiring new Login Server or Storage Provider APIs.