//! Deterministic lifecycle demo plus the negative tests the task requires.
//!
//! Every test uses `FakeClock`, so nothing here sleeps and nothing here is timing-dependent.

use std::sync::Arc;

use upspa_mobile_ffi::contract::*;
use upspa_mobile_ffi::engine::MobileEngine;
use upspa_mobile_ffi::fakes::*;

const T0: u64 = 1_700_000_000_000;

struct Harness {
    engine: Arc<MobileEngine>,
    clock: Arc<FakeClock>,
    diagnostics: Arc<RecordingDiagnostics>,
}

fn harness() -> Harness {
    let clock = Arc::new(FakeClock::new(T0));
    let diagnostics = Arc::new(RecordingDiagnostics::default());
    let engine = MobileEngine::new(
        Arc::new(FakeTransport::default()),
        Arc::new(FakeSecureStorage::default()),
        clock.clone(),
        Arc::new(FakeIdentity::default()),
        diagnostics.clone(),
    );
    Harness {
        engine,
        clock,
        diagnostics,
    }
}

fn probe(deadline_millis: u64) -> MobileCommand {
    MobileCommand {
        contract_version: MOBILE_CONTRACT_VERSION,
        request_tag: "probe".to_owned(),
        deadline: Deadline {
            epoch_millis: deadline_millis,
        },
        body: CommandBody::Probe {
            echo_tag: "lifecycle-demo".to_owned(),
        },
    }
}

/// The one deterministic command -> effect -> event lifecycle the deliverable asks for.
#[test]
fn deterministic_probe_lifecycle() {
    let h = harness();

    // 1. Command in.
    let effect = h.engine.submit(probe(T0 + 5_000)).expect("submit accepted");

    // 2. Effect out, correlated and version-stamped.
    assert_eq!(effect.contract_version, MOBILE_CONTRACT_VERSION);
    assert!(
        effect.operation.value.starts_with("op-") && effect.operation.value.ends_with("-000001")
    );
    assert_eq!(
        effect.body,
        EffectBody::AckImmediately {
            echo_tag: "lifecycle-demo".to_owned()
        }
    );
    assert_eq!(h.engine.open_operation_count(), 1);

    // 3. Host runs the effect and reports back; event out.
    h.clock.advance(250);
    let event = h
        .engine
        .deliver(
            effect.operation.clone(),
            HostOutcome::ProbeAck {
                echo_tag: "lifecycle-demo".to_owned(),
            },
        )
        .expect("outcome accepted");

    assert_eq!(event.operation, effect.operation);
    assert_eq!(event.sequence, 1);
    assert!(event.is_success());
    assert_eq!(h.engine.open_operation_count(), 0);

    // Diagnostics saw codes only.
    assert_eq!(
        h.diagnostics.codes(),
        vec!["operation.started", "operation.settled"]
    );
}

/// NEGATIVE TEST 1: an expired operation cannot be reported as successful.
#[test]
fn stale_operation_cannot_be_reported_successful() {
    let h = harness();
    let effect = h.engine.submit(probe(T0 + 1_000)).expect("submit accepted");

    // Push the clock past the deadline.
    h.clock.advance(1_001);

    let result = h.engine.deliver(
        effect.operation.clone(),
        // The host lies and claims success.
        HostOutcome::ProbeAck {
            echo_tag: "lifecycle-demo".to_owned(),
        },
    );

    match result {
        Err(MobileError::OperationExpired {
            operation,
            deadline_millis,
            now_millis,
        }) => {
            assert_eq!(operation, effect.operation.value);
            assert_eq!(deadline_millis, T0 + 1_000);
            assert!(now_millis > deadline_millis);
        }
        other => panic!("expected OperationExpired, got {other:?}"),
    }

    // And the operation stays closed: a retry cannot resurrect it either.
    assert!(matches!(
        h.engine.deliver(
            effect.operation,
            HostOutcome::ProbeAck {
                echo_tag: "lifecycle-demo".to_owned()
            }
        ),
        Err(MobileError::OperationAlreadySettled { .. })
    ));
}

/// NEGATIVE TEST 2: an operation this engine never issued cannot be reported as successful.
#[test]
fn unknown_operation_cannot_be_reported_successful() {
    let h = harness();

    let result = h.engine.deliver(
        OperationId::new("op-999999"),
        HostOutcome::RequestSucceeded {
            response: vec![1, 2, 3],
        },
    );

    assert!(matches!(
        result,
        Err(MobileError::UnknownOperation { .. })
    ));
    assert!(matches!(
        h.engine.cancel(OperationId::new("op-999999")),
        Err(MobileError::UnknownOperation { .. })
    ));
}

#[test]
fn cancelled_operation_is_terminal() {
    let h = harness();
    let effect = h.engine.submit(probe(T0 + 5_000)).expect("submit accepted");

    let cancelled = h
        .engine
        .cancel(effect.operation.clone())
        .expect("cancel accepted");
    assert!(!cancelled.is_success());
    assert!(matches!(
        cancelled.body,
        EventBody::OperationCancelled { .. }
    ));

    assert!(matches!(
        h.engine.deliver(
            effect.operation,
            HostOutcome::ProbeAck {
                echo_tag: "lifecycle-demo".to_owned()
            }
        ),
        Err(MobileError::OperationAlreadySettled { .. })
    ));
}

#[test]
fn old_host_contract_version_is_rejected() {
    let h = harness();
    let mut command = probe(T0 + 5_000);
    command.contract_version = 1;

    assert!(matches!(
        h.engine.submit(command),
        Err(MobileError::UnsupportedContractVersion { host: 1, .. })
    ));
}

#[test]
fn command_arriving_already_expired_never_becomes_an_operation() {
    let h = harness();
    assert!(matches!(
        h.engine.submit(probe(T0 - 1)),
        Err(MobileError::OperationExpired { .. })
    ));
    assert_eq!(h.engine.open_operation_count(), 0);
}

#[test]
fn mismatched_outcome_is_rejected() {
    let h = harness();
    let effect = h.engine.submit(probe(T0 + 5_000)).expect("submit accepted");
    assert!(matches!(
        h.engine.deliver(effect.operation, HostOutcome::BlobWritten),
        Err(MobileError::OutcomeMismatch { .. })
    ));
}

/// REVIEW #3: cancelling an operation whose deadline has passed must settle it as expired and
/// return the typed `OperationExpired` error, never an `OperationCancelled` event.
#[test]
fn cancelling_an_expired_operation_returns_operation_expired() {
    let h = harness();
    let effect = h.engine.submit(probe(T0 + 1_000)).expect("submit accepted");

    h.clock.advance(1_001);

    match h.engine.cancel(effect.operation.clone()) {
        Err(MobileError::OperationExpired {
            operation,
            deadline_millis,
            now_millis,
        }) => {
            assert_eq!(operation, effect.operation.value);
            assert_eq!(deadline_millis, T0 + 1_000);
            assert_eq!(now_millis, T0 + 1_001);
        }
        other => panic!("expected OperationExpired, got {other:?}"),
    }

    // Settled as expired: closed, and neither a second cancel nor a late delivery can reopen it.
    assert_eq!(h.engine.open_operation_count(), 0);
    assert!(matches!(
        h.engine.cancel(effect.operation.clone()),
        Err(MobileError::OperationAlreadySettled { .. })
    ));
    assert!(matches!(
        h.engine.deliver(
            effect.operation,
            HostOutcome::ProbeAck {
                echo_tag: "lifecycle-demo".to_owned()
            }
        ),
        Err(MobileError::OperationAlreadySettled { .. })
    ));
    assert_eq!(
        h.diagnostics.codes(),
        vec!["operation.started", "operation.expired"]
    );
}

/// Cancellation exactly at the deadline is still allowed (expiry is strictly `now > deadline`).
#[test]
fn cancelling_at_the_deadline_is_a_cancellation() {
    let h = harness();
    let effect = h.engine.submit(probe(T0 + 1_000)).expect("submit accepted");
    h.clock.advance(1_000);
    let event = h.engine.cancel(effect.operation).expect("cancel accepted");
    assert!(matches!(event.body, EventBody::OperationCancelled { .. }));
}

const HOSTILE_TAG: &str = "alice@example.com:hunter2-master-secret";

fn hostile_probe(deadline_millis: u64) -> MobileCommand {
    let mut command = probe(deadline_millis);
    command.request_tag = HOSTILE_TAG.to_owned();
    command
}

fn assert_tag_absent(h: &Harness, extra: &[String]) {
    for field in h.diagnostics.all_fields().iter().chain(extra) {
        assert!(
            !field.contains("hunter2") && !field.contains("alice"),
            "host request tag leaked into engine output"
        );
    }
}

/// REVIEW #4: host-controlled request tags never reach diagnostics or operation IDs.
#[test]
fn hostile_request_tag_never_reaches_diagnostics_on_the_happy_path() {
    let h = harness();
    let effect = h
        .engine
        .submit(hostile_probe(T0 + 5_000))
        .expect("submit accepted");
    let event = h
        .engine
        .deliver(
            effect.operation.clone(),
            HostOutcome::ProbeAck {
                echo_tag: "lifecycle-demo".to_owned(),
            },
        )
        .expect("delivered");

    assert!(effect.operation.value.ends_with("-000001"));
    assert!(!h.diagnostics.all_fields().is_empty());
    assert_tag_absent(&h, &[effect.operation.value, event.operation.value]);
}

/// REVIEW #4: the early-rejection path (command already expired) must not leak the tag either,
/// neither into diagnostics nor into the typed error that becomes a Kotlin exception message.
#[test]
fn hostile_request_tag_never_reaches_diagnostics_on_early_rejection() {
    let h = harness();
    let err = h
        .engine
        .submit(hostile_probe(T0 - 1))
        .expect_err("already expired");

    let MobileError::OperationExpired { ref operation, .. } = err else {
        panic!("expected OperationExpired, got {err:?}");
    };
    assert_eq!(h.diagnostics.codes(), vec!["command.rejected"]);
    assert_tag_absent(&h, &[operation.clone(), err.to_string()]);

    // The correlation ID minted for the rejection was never registered.
    assert!(matches!(
        h.engine.cancel(OperationId::new(operation.clone())),
        Err(MobileError::UnknownOperation { .. })
    ));
}

/// REVIEW #4: hostile tags on cancellation and expiry paths stay out of diagnostics too.
#[test]
fn hostile_request_tag_never_reaches_diagnostics_on_cancel_and_expiry() {
    let h = harness();
    let a = h.engine.submit(hostile_probe(T0 + 5_000)).expect("a");
    let b = h.engine.submit(hostile_probe(T0 + 1_000)).expect("b");
    h.engine.cancel(a.operation).expect("cancelled");
    h.clock.advance(1_001);
    let _ = h.engine.cancel(b.operation);
    assert_eq!(
        h.diagnostics.codes(),
        vec![
            "operation.started",
            "operation.started",
            "operation.cancelled",
            "operation.expired"
        ]
    );
    assert_tag_absent(&h, &[]);
}

const PRIVATE_MARKER: &str = "SYNTHETIC_PRIVATE_IDENTIFIER_123";

fn ack() -> HostOutcome {
    HostOutcome::ProbeAck {
        echo_tag: "lifecycle-demo".to_owned(),
    }
}

/// REVIEW 2 / P1: an acknowledgement for engine A's operation cannot settle engine B's operation,
/// even though both are the first operation of their engine and use the same probe tag.
#[test]
fn acknowledgement_from_another_engine_instance_is_rejected() {
    let a = harness();
    let b = harness();
    let op_a = a.engine.submit(probe(T0 + 5_000)).expect("a").operation;
    a.engine.deliver(op_a.clone(), ack()).expect("a settles");
    let op_b = b.engine.submit(probe(T0 + 5_000)).expect("b").operation;

    assert_ne!(op_a, op_b);
    assert!(matches!(
        b.engine.deliver(op_a, ack()),
        Err(MobileError::UnknownOperation { .. })
    ));
    assert_eq!(b.engine.open_operation_count(), 1);
    // Control: B's own acknowledgement is still accepted.
    assert!(b
        .engine
        .deliver(op_b, ack())
        .expect("b settles")
        .is_success());
}

/// REVIEW 2 / P2: a host failure reason is mapped to a fixed engine code, never reflected.
#[test]
fn host_failure_reason_is_not_reflected() {
    let h = harness();
    let op = h
        .engine
        .submit(probe(T0 + 5_000))
        .expect("submit")
        .operation;
    let event = h
        .engine
        .deliver(
            op,
            HostOutcome::HostFailed {
                reason_code: PRIVATE_MARKER.to_owned(),
            },
        )
        .expect("failure event");
    assert!(!event.is_success());
    assert!(!format!("{event:?}").contains(PRIVATE_MARKER));
    assert_eq!(
        event.body,
        EventBody::OperationFailed {
            reason_code: "host-failure-unclassified".to_owned()
        }
    );

    // Control: an allowlisted code still gets through.
    let op = h
        .engine
        .submit(probe(T0 + 5_000))
        .expect("submit")
        .operation;
    let event = h
        .engine
        .deliver(
            op,
            HostOutcome::HostFailed {
                reason_code: "timeout".to_owned(),
            },
        )
        .expect("failure event");
    assert_eq!(
        event.body,
        EventBody::OperationFailed {
            reason_code: "timeout".to_owned()
        }
    );
}

/// REVIEW 2 / P2: an unknown operation ID is not echoed back in the error.
#[test]
fn unknown_operation_id_is_not_echoed() {
    let h = harness();
    let forged = OperationId::new(format!("op-{PRIVATE_MARKER}"));
    for err in [
        h.engine
            .deliver(forged.clone(), ack())
            .expect_err("unknown"),
        h.engine.cancel(forged.clone()).expect_err("unknown"),
    ] {
        assert!(matches!(err, MobileError::UnknownOperation { .. }));
        assert!(!format!("{err:?} {err}").contains(PRIVATE_MARKER));
    }
}

/// REVIEW 2 / P2: credential derivation is a placeholder, so a transport "success" (even an empty
/// one) must not be reported as a ready credential.
#[test]
fn placeholder_derivation_never_reports_credential_success() {
    let h = harness();
    let effect = h
        .engine
        .submit(MobileCommand {
            contract_version: MOBILE_CONTRACT_VERSION,
            request_tag: "derive".to_owned(),
            deadline: Deadline {
                epoch_millis: T0 + 5_000,
            },
            body: CommandBody::DeriveCredential {
                selector: AccountSelector {
                    site_tag: "example.org".to_owned(),
                    account_label: "user".to_owned(),
                    policy_revision: 1,
                },
                master_secret: SecretBytes::new(b"synthetic-master-secret".to_vec()),
            },
        })
        .expect("submit");
    let event = h
        .engine
        .deliver(
            effect.operation,
            HostOutcome::RequestSucceeded { response: vec![] },
        )
        .expect("event");
    assert!(!event.is_success());
    assert_eq!(
        event.body,
        EventBody::OperationFailed {
            reason_code: "derivation-unimplemented".to_owned()
        }
    );
}
