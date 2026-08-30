# Pre-registration

Owner: Göktuğ Sina Bekçioğulları  
Track: Protocol Workflows, Distributed Consistency, and Recovery

This document records the expected classifications before using the simulator results as evidence.

The purpose is to avoid changing the interpretation after seeing the output.

---

## H1: Two-of-three Storage Provider threshold is enough for recoverability

Measurement:

- Number of successful Storage Provider writes after a Login Server success.

Threshold:

- At least 2 successful Storage Provider writes out of 3.

Verdict:

- If 0/3 or 1/3 Storage Providers succeed, the operation is not durable.
- If 2/3 Storage Providers succeed, the operation is threshold-committed but degraded.
- If 3/3 Storage Providers succeed, the operation is fully committed.

Expected recovery rule:

- 0/3 and 1/3: keep pending and retry.
- 2/3: allow recovery but reconcile the missing Storage Provider.
- 3/3: clear pending operation.

---

## H2: Process death before Login Server submission is safe to cancel or restart

Measurement:

- Crash point is before Login Server submission.

Threshold:

- No external Login Server side effect has happened.

Verdict:

- The operation can be safely cancelled or restarted from the local journal.
- No new Login Server or Storage Provider API is required for this case.

---

## H3: Process death or timeout after Login Server submission is ambiguous

Measurement:

- Login Server result is timeout or unknown after submission.

Threshold:

- The client cannot prove whether the Login Server applied the operation.

Verdict:

- The operation must be classified as ambiguous.
- The pending operation should not be silently cleared.
- A Login Server status API, idempotency API, or manual confirmation is required to resolve the ambiguity.

---

## H4: Partial master-password update is more dangerous than ordinary partial registration

Measurement:

- Master-password update succeeds on only some Storage Providers.

Threshold:

- Fewer than all Storage Providers hold the same version or cid.

Verdict:

- If fewer than 2 Storage Providers succeed, the update is unsafe and should remain pending.
- If exactly 2 Storage Providers succeed, the update may be recoverable but version-divergent.
- The client must use version or cid checks before trusting the new state.

---

## H5: Successful Storage Provider responses are not enough if cid values diverge

Measurement:

- All Storage Providers respond successfully, but their cid or version values are not consistent.

Threshold:

- Returned cid/version values disagree across Storage Providers.

Verdict:

- The operation should not be treated as fully healthy.
- The client should classify the state as committed but divergent.
- The client must reconcile Storage Providers before clearing the operation as fully replicated.

---

## Rejected Alternative

Rejected alternative:

- Full rollback or distributed transaction across Login Server and Storage Providers.

Reason:

- An unmodified Login Server cannot participate in a real two-phase commit or distributed transaction protocol.
- The client cannot always undo a Login Server-side effect after it has already happened.
- Therefore, forward recovery and reconciliation are safer than promising rollback.

---

## Expected simulator coverage

The simulator should cover:

- 0/3 Storage Provider successes,
- 1/3 Storage Provider successes,
- 2/3 Storage Provider successes,
- 3/3 Storage Provider successes,
- process death before Login Server submission,
- process death after Login Server submission,
- process death before Storage Provider commit,
- process death after partial Storage Provider commit,
- partial master-password update,
- Login Server timeout after submission,
- divergent cid values.

---

## Evidence artifact

The executable artifact is:

`code-protocol-sim/simulator.py`

The expected output is:

- `results.json`
- a printed summary of all evaluated scenarios
- classification of each scenario into durable, degraded, ambiguous, unsafe, or divergent states