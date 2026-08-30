#!/usr/bin/env python3

import json
from pathlib import Path
from typing import Any, Dict, List, Optional


THRESHOLD = 2
TOTAL_SPS = 3


def count_sp_successes(sp_results: Optional[List[str]]) -> Optional[int]:
    if sp_results is None:
        return None
    return sum(1 for result in sp_results if result == "success")


def evaluate_scenario(scenario: Dict[str, Any]) -> Dict[str, Any]:
    name = scenario["name"]
    operation_type = scenario["operation_type"]
    ls_result = scenario["ls_result"]
    sp_results = scenario.get("sp_results")
    crash_point = scenario.get("crash_point", "none")
    threshold = scenario.get("threshold", THRESHOLD)
    divergent_cid = scenario.get("divergent_cid", False)

    sp_successes = count_sp_successes(sp_results)

    if sp_successes is None:
        threshold_met = "unknown"
    else:
        threshold_met = sp_successes >= threshold

    client_visible_result = ""
    recovery_action = ""
    atomicity_note = ""
    requires_new_api = False

    if divergent_cid:
        client_visible_result = "committed_but_divergent"
        recovery_action = "Do not treat the state as fully healthy. Compare versions/cid values, select the newest valid threshold state, and reconcile divergent Storage Providers."
        atomicity_note = "All Storage Providers may respond successfully while still holding divergent cid/version values. Success count alone is not enough."
        requires_new_api = False

    elif crash_point == "before_ls_submission":
        client_visible_result = "safe_to_cancel_or_restart"
        recovery_action = "No external Login Server effect exists yet. The client can cancel or restart the operation from the journal."
        atomicity_note = "No distributed atomicity problem yet because no external side effect has happened."

    elif crash_point == "after_ls_submission" and ls_result in ["timeout", "unknown"]:
        client_visible_result = "ambiguous"
        recovery_action = "The client cannot know whether the Login Server applied the request. Keep the pending operation and require LS status/idempotency API or manual confirmation."
        atomicity_note = "This cannot be made atomic with an unmodified Login Server."
        requires_new_api = True

    elif crash_point == "before_sp_commit" and ls_result == "success":
        client_visible_result = "ls_changed_but_sp_not_durable"
        recovery_action = "Retry Storage Provider writes using the same operation ID. Do not show final success until the SP threshold is reached."
        atomicity_note = "The LS may already be updated while SP state is missing, so rollback is not reliable."

    elif ls_result == "fail":
        client_visible_result = "failed_before_commit"
        recovery_action = "Clear or mark the pending operation as failed if no Storage Provider writes were committed."
        atomicity_note = "If the LS definitely failed before applying the request, the operation can be safely failed."

    elif ls_result == "success":
        if sp_successes is None:
            client_visible_result = "unknown_sp_state"
            recovery_action = "Recover from the local journal and query/retry Storage Provider writes if possible."
            atomicity_note = "SP state is unknown after recovery."

        elif operation_type == "master_password_update":
            if sp_successes >= threshold and sp_successes < TOTAL_SPS:
                client_visible_result = "committed_but_version_divergent"
                recovery_action = "Allow recovery only with version checks. Reconcile missing Storage Providers before relying on the new version everywhere."
                atomicity_note = "Partial master-password updates are dangerous because SPs may hold different versions."
            elif sp_successes == TOTAL_SPS:
                client_visible_result = "fully_committed"
                recovery_action = "Clear pending operation."
                atomicity_note = "All Storage Providers reached the same version."
            else:
                client_visible_result = "unsafe_partial_update"
                recovery_action = "Keep pending. Do not consider the master-password update durable. Retry or require manual recovery."
                atomicity_note = "Less than threshold success means the new state is not safely recoverable."

        elif sp_successes < threshold:
            client_visible_result = "not_durable"
            recovery_action = "Keep pending and retry Storage Provider writes. Do not show final success."
            atomicity_note = "The LS succeeded, but fewer than threshold SPs succeeded."

        elif sp_successes == threshold and sp_successes < TOTAL_SPS:
            client_visible_result = "committed_but_degraded"
            recovery_action = "Show success only as threshold-committed. Reconcile missing Storage Providers in the background."
            atomicity_note = "The operation is recoverable with 2/3 SPs, but not fully replicated."

        elif sp_successes == TOTAL_SPS:
            client_visible_result = "fully_committed"
            recovery_action = "Clear pending operation."
            atomicity_note = "All Storage Providers succeeded."

    elif ls_result == "timeout":
        client_visible_result = "ambiguous"
        recovery_action = "Keep pending. Without an LS status or idempotency API, the client cannot know whether the LS applied the operation."
        atomicity_note = "Timeout after submission is not distinguishable from success with lost response."
        requires_new_api = True

    else:
        client_visible_result = "unclassified"
        recovery_action = "Manual analysis required."
        atomicity_note = "The simulator does not have enough information to classify this state."

    return {
        "name": name,
        "operation_type": operation_type,
        "ls_result": ls_result,
        "sp_results": sp_results,
        "sp_successes": sp_successes,
        "threshold": threshold,
        "threshold_met": threshold_met,
        "crash_point": crash_point,
        "divergent_cid": divergent_cid,
        "client_visible_result": client_visible_result,
        "recovery_action": recovery_action,
        "requires_new_api": requires_new_api,
        "atomicity_note": atomicity_note,
    }


def main() -> None:
    scenario_path = Path("scenarios.json")
    output_path = Path("results.json")

    if not scenario_path.exists():
        raise FileNotFoundError("scenarios.json not found")

    scenarios = json.loads(scenario_path.read_text())
    results = [evaluate_scenario(scenario) for scenario in scenarios]

    output = {
        "model": {
            "total_storage_providers": TOTAL_SPS,
            "threshold": THRESHOLD,
            "login_server_modified": False,
        },
        "results": results,
    }

    output_path.write_text(json.dumps(output, indent=2))

    print("UpSPA protocol failure simulation")
    print("=" * 40)
    print(f"Scenarios evaluated: {len(results)}")
    print(f"Results written to: {output_path}")
    print()

    for result in results:
        print(f"- {result['name']}")
        print(f"  LS: {result['ls_result']}")
        print(f"  SP successes: {result['sp_successes']}")
        print(f"  Threshold met: {result['threshold_met']}")
        print(f"  Client result: {result['client_visible_result']}")
        print(f"  Recovery: {result['recovery_action']}")
        print()


if __name__ == "__main__":
    main()
