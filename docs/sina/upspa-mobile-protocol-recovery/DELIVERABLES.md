# Deliverables Map

Owner: Göktuğ Sina Bekçioğulları  
Track: Protocol Workflows, Distributed Consistency, and Recovery

This file maps the research-task requirements to the files in this package.

---

## Required deliverables

| # | Requirement | File | State |
|---|---|---|---|
| 1 | Research report | `FINDINGS.md` | initial draft done |
| 2 | Source ledger with links, versions, dates, claims, and verification status | `reports/SOURCE-LEDGER.md` | to be completed |
| 3 | Comparison of at least two credible alternatives | `FINDINGS.md`, Section 7 | initial draft done |
| 4 | Reproducible evidence artifact | `code-protocol-sim/simulator.py` | done, runs successfully |
| 5 | Recommendation for each assigned architectural question | `FINDINGS.md`, Section 10 | initial draft done |
| 6 | Newly discovered risks and open problems | `FINDINGS.md`, Section 9 | initial draft done |
| 7 | Short presentation | `presentation.pptx` | done |

---

## Minimum evidence package

| Requirement | Evidence | State |
|---|---|---|
| Five primary sources | `reports/SOURCE-LEDGER.md` | preliminary evidence included|
| Two credible security or engineering analyses | `reports/SOURCE-LEDGER.md` | to be completed |
| Two inspected example codebases | `reports/CODEBASE-INSPECTION.md` | to be completed |
| One reproducible experiment or prototype | `code-protocol-sim/simulator.py` and `code-protocol-sim/results.json` | done |
| One seriously evaluated rejected alternative | Full rollback / distributed transaction, `FINDINGS.md`, Section 7 | initial draft done |
| One explicit attempt to disprove the preferred recommendation | `reports/PRE-REGISTRATION.md` | initial draft done |

---

## Simulator evidence

The simulator was run successfully.

Command:

```bash
cd code-protocol-sim
python3 simulator.py