# Codebase Inspection

Owner: Göktuğ Sina Bekçioğulları  
Track: Protocol Workflows, Distributed Consistency, and Recovery

This file records project code and documentation that should be inspected for the protocol recovery research.

The purpose is to verify whether the simulator assumptions match the current UpSPA implementation.

---

## 1. UpSPA Extension repository

| Field | Value |
|---|---|
| Repository | Current local repository: `UpSPA_extension` |
| Local path | `~/SPA-project/UpSPA_extension` |
| Branch | `intern/sina` |
| Inspection status | in progress |
| License | to verify |
| Commit | to record before final submission |

### Files and areas to inspect

| Area | Why it matters |
|---|---|
| `packages/extension/src/background/` | Background/service-worker flow may contain registration, confirmation, and recovery logic. |
| `packages/extension/src/popup/` | Popup state controls pending registration, manual confirmation, lock behavior, and user-visible state. |
| `packages/extension/src/content/` | Content script controls form detection and site interaction. |
| `packages/upspa-js/src/` | Client-side protocol calls and SP/LS communication may be implemented here. |
| `docs/apis.md` | API payloads and invariants can show whether current APIs support idempotency, status queries, or version checks. |
| `docs/protocol-phases.md` | Protocol phase ordering can show which operations happen before/after LS and SP effects. |

### Patterns to verify

- Whether registration writes to the Login Server before or after Storage Provider writes.
- Whether SP writes already include stable operation IDs.
- Whether LS APIs expose status or idempotent confirmation.
- Whether master-password update state is versioned.
- Whether current client can detect divergent `cid` values.
- Whether pending registration is stored durably or only in session/popup state.
- Whether retry after timeout is safe.

### Preliminary interpretation

The simulator currently assumes that:

- the Login Server is unmodified,
- Storage Providers can independently succeed, fail, or time out,
- the client can keep a durable pending-operation journal,
- 2/3 Storage Provider success is the threshold for recoverability,
- and missing Storage Provider writes can be reconciled later.

These assumptions must be checked against the current source code before the final recommendation is marked complete.

---

## 2. UpSPA User Study Version repository

| Field | Value |
|---|---|
| Repository | `UpSPA_Extension_User_Study_Version` |
| Local path | to verify |
| Inspection status | not started |
| License | to verify |
| Commit | to record if inspected |

### Why inspect it?

The user-study version may contain different browser-extension workflows, supported site definitions, migration logic, or credential metadata compared with the main extension repository.

### What to inspect

- Site account schema.
- Credential record schema.
- Migration or imported-password logic.
- Supported site metadata.
- Any changes to SP or LS client APIs.

---

## 3. External example codebases

The research brief asks for two inspected example codebases. For this track, the most useful examples should be chosen based on recovery/idempotency/reconciliation patterns, not UI framework patterns.

Candidate examples:

| Candidate | Why useful | Status |
|---|---|---|
| A small open-source mobile credential manager | Could show durable local state and safe recovery patterns. | not selected |
| A distributed workflow/saga sample implementation | Could show compensating transactions and idempotent recovery. | not selected |
| UpSPA's own browser extension and user-study version | Directly relevant to current protocol behavior. | partially selected |

Final codebase choices must include:

- repository URL,
- commit hash or release tag,
- license,
- inspected files,
- useful patterns,
- unsuitable or unsafe patterns.

---

## 4. Initial findings to confirm

| Finding | Current status |
|---|---|
| Full LS + SP atomicity is not possible unless LS participates in a transaction/status/idempotency protocol. | supported by architecture reasoning, needs project-code confirmation |
| 2/3 SP success can be treated as recoverable but degraded. | supported by simulator, needs protocol confirmation |
| 0/3 and 1/3 SP success should not be shown as final success. | supported by simulator |
| Timeout after LS submission is ambiguous. | supported by simulator and idempotency reasoning |
| Partial master-password update requires version/cid checks. | supported by simulator, needs API/code confirmation |
| Divergent `cid` values require reconciliation even if all SP calls return success. | supported by simulator, needs API/code confirmation |

---

## 5. Safety notes

- No proprietary application should be decompiled.
- No real credentials or secrets should be used in experiments.
- No copied GPL code should be inserted into UpSPA artifacts.
- Codebase findings should be recorded as prose unless license compatibility is explicitly checked.