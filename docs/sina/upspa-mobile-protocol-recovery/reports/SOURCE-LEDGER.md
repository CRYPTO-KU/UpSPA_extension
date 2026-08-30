# Source Ledger

Owner: Göktuğ Sina Bekçioğulları  
Track: Protocol Workflows, Distributed Consistency, and Recovery  
Accessed: 2026-08-30

This ledger records sources, claims, and verification status for the UpSPA mobile protocol recovery research.

---

## Source level scale

| Level | Meaning | Example |
|---|---|---|
| L1 | Primary specification, official documentation, or project source code | RFC, Android docs, Apple docs, PostgreSQL docs, UpSPA source |
| L2 | Academic paper or official engineering documentation | Saga paper, Microsoft/Azure architecture patterns |
| L3 | Credible engineering/security analysis | Engineering blog, security guidance |
| L4 | Forum, community discussion, or unverified secondary source | Developer forum report |

---

## Primary sources and standards

| ID | Source | Type | Claim supported | Verification status |
|---|---|---|---|---|
| S1 | Android Developers — Save UI states | Official Android documentation | Android apps must preserve state across system-initiated process death; small serialized state can survive process recreation, but durable operation state should not rely only on UI state. | verified |
| S2 | Android Developers — Processes and app lifecycle | Official Android documentation | Android may kill app processes based on lifecycle and system memory pressure; mobile clients must assume process death can happen. | verified |
| S3 | Apple Developer — Managing your app's life cycle | Official Apple documentation | iOS apps move through active, background, suspended, and termination-related lifecycle states; long-running assumptions are unsafe. | verified |
| S4 | RFC 9110 — HTTP Semantics, idempotent methods | IETF standard | Idempotency means multiple identical requests have the same intended effect as one request; this supports stable operation IDs and retry-safe APIs. | verified |
| S5 | PostgreSQL documentation — Two-Phase Transactions | Official PostgreSQL documentation | Real distributed transactions require participants that support prepare/commit/rollback phases; an unmodified Login Server cannot be assumed to participate in 2PC. | verified |
| S6 | Rustonomicon — FFI | Official Rust documentation | If a shared Rust core is later used from mobile, FFI boundaries need explicit error/panic handling and safe interface design. | verified |
| S7 | OWASP MASVS | Official OWASP security standard | Mobile applications must protect sensitive data at rest and handle storage/security boundaries carefully; pending journals must avoid real secrets in logs or fixtures. | verified |

---

## Engineering analyses and academic sources

| ID | Source | Type | Claim supported | Verification status |
|---|---|---|---|---|
| S8 | Azure Architecture Center — Compensating Transaction pattern | Official engineering architecture guidance | When distributed operations span multiple services and cannot be one atomic transaction, compensating/forward recovery is a practical pattern. | verified |
| S9 | Garcia-Molina and Salem — Sagas paper | Academic paper | Long-lived or multi-step transactions can be decomposed into steps with compensating actions; useful background for rollback vs forward recovery tradeoffs. | verified |

---

## Project sources to inspect

| ID | Source | Type | Claim supported | Verification status |
|---|---|---|---|---|
| P1 | UpSPA extension repository — current working repository | Project source code | Current extension and protocol workflow should be inspected to verify operation ordering and existing LS/SP API behavior. | to inspect |
| P2 | UpSPA docs/API markdown files | Project documentation | Current API payloads, encoding rules, and Storage Provider/Login Server assumptions should be checked before final recommendation. | to inspect |

---

## Claims supported by the ledger

| Claim | Supporting sources |
|---|---|
| Mobile process death must be expected and recovery state must be durable. | S1, S2, S3 |
| Retry safety requires idempotent operation design. | S4 |
| Full distributed atomicity requires all participants to support a transaction protocol. | S5 |
| With an unmodified Login Server, LS + SP operations cannot be assumed to be one atomic transaction. | S5, P1, P2 |
| Forward recovery and reconciliation are more realistic than promising rollback. | S8, S9 |
| Pending-operation journals must avoid storing secrets in unsafe form. | S7 |
| If Rust core is reused on mobile later, FFI boundaries must be explicit and safe. | S6 |

---

## Notes

AI-assisted search was used only for discovery. Claims in the report should be based on the sources above, inspected project code, or the reproducible simulator.