#!/usr/bin/env python3
"""Render only observed GitHub step outcomes; missing steps are not passes."""
import json
import os

CHECKS = {
    "toolchains": "Selected toolchains",
    "dependencies": "Node dependencies",
    "rust_corpus": "Rust corpus",
    "rust_properties": "Rust properties and boundaries",
    "ts_corpus": "TypeScript corpus",
    "regeneration": "Deterministic regeneration",
    "corruption": "Safe corruption detection",
    "security": "Security gates and fixtures",
    "formatting": "Rust formatting",
    "controls": "Qualification regression controls",
}


def render(steps):
    lines = ["## Encoder Conformance Results", "", "| Check | Outcome |",
             "| :--- | :--- |"]
    for step_id, label in CHECKS.items():
        outcome = steps.get(step_id, {}).get("outcome", "skipped")
        if outcome not in {"success", "failure", "cancelled", "skipped"}:
            outcome = "unknown"
        lines.append(f"| {label} | {outcome} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    print(render(json.loads(os.environ["ENCODER_STEPS_JSON"])), end="")
