# UpSPA Mobile Protocol Workflows, Distributed Consistency, and Recovery

Owner: Göktuğ Sina Bekçioğulları  
Track: UpSPA mobile architecture research

This package investigates how UpSPA mobile operations should behave when an unmodified Login Server and multiple Storage Providers succeed, fail, or time out independently.

The main focus is on:

- operation workflows,
- distributed consistency,
- pending operations,
- process death recovery,
- idempotency,
- staged writes,
- threshold behavior,
- Storage Provider reconciliation,
- and what cannot be atomic with an unmodified Login Server.

## Layout

```text
FINDINGS.md                         main research report
DELIVERABLES.md                     requirement-to-file map
reports/
  SOURCE-LEDGER.md                  sources, claims, versions, verification status
  PRE-REGISTRATION.md               hypotheses and expected verdicts before running simulations
  PROCEDURES.md                     how to reproduce the simulations
  CODEBASE-INSPECTION.md            inspected repositories and useful patterns
  RETRACTIONS.md                    visible corrections and withdrawn claims
code-protocol-sim/
  simulator.py                      executable failure simulator
  scenarios.json                    scenario definitions
  results.json                      simulator output
  README.md                         simulator usage