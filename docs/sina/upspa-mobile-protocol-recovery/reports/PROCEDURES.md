# Experiment Procedures

Owner: Göktuğ Sina Bekçioğulları  
Track: Protocol Workflows, Distributed Consistency, and Recovery

This document explains how to reproduce the protocol failure simulation.

The simulator is intentionally small. It is not a production implementation. It is a model-based evidence artifact for reasoning about UpSPA mobile recovery behavior.

---

## Experiment: Protocol Failure Simulator

### Question

How should the UpSPA mobile client classify operations when the Login Server and three Storage Providers succeed, fail, or time out independently?

### Location


code-protocol-sim/
  simulator.py
  scenarios.json
  results.json