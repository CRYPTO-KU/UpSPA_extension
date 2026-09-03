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
    assert_eq!(effect.operation.value, "op-000001-probe");
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
        OperationId::new("op-999999-forged"),
        HostOutcome::RequestSucceeded {
            response: vec![1, 2, 3],
        },
    );

    assert!(matches!(
        result,
        Err(MobileError::UnknownOperation { .. })
    ));
    assert!(matches!(
        h.engine.cancel(OperationId::new("op-999999-forged")),
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
