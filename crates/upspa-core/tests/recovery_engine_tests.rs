use upspa_core::recovery_engine::{
    Effect, Event, OperationConfig, OperationEngine, OperationId, OperationStatus,
    RecoveryEngineError, RequestId, StorageProviderId,
};

fn op_id() -> OperationId {
    OperationId("op-1".to_string())
}

fn provider(id: &str) -> StorageProviderId {
    StorageProviderId(id.to_string())
}

fn default_config() -> OperationConfig {
    OperationConfig {
        operation_id: op_id(),
        storage_providers: vec![provider("sp-1"), provider("sp-2"), provider("sp-3")],
        threshold: 2,
        deadline_ms: Some(1_000),
    }
}

fn start_default() -> (OperationEngine, Vec<Effect>) {
    OperationEngine::start(default_config()).expect("engine should start")
}

fn request_for(effects: &[Effect], provider_id: &str) -> RequestId {
    effects
        .iter()
        .find_map(|effect| match effect {
            Effect::SendStorageProviderRequest {
                request_id,
                provider_id: actual_provider,
                ..
            } if actual_provider.0 == provider_id => Some(request_id.clone()),
            _ => None,
        })
        .expect("request should exist")
}

fn reply(provider_id: &str, request_id: RequestId, digest: &str) -> Event {
    Event::StorageProviderReply {
        operation_id: op_id(),
        request_id,
        provider_id: provider(provider_id),
        accepted: true,
        state_digest: digest.to_string(),
    }
}

fn rejected_reply(provider_id: &str, request_id: RequestId, digest: &str) -> Event {
    Event::StorageProviderReply {
        operation_id: op_id(),
        request_id,
        provider_id: provider(provider_id),
        accepted: false,
        state_digest: digest.to_string(),
    }
}

#[test]
fn start_emits_one_request_per_storage_provider() {
    let (engine, effects) = start_default();

    assert_eq!(engine.status(), &OperationStatus::Pending);
    assert_eq!(effects.len(), 3);

    let mut providers = effects
        .iter()
        .map(|effect| match effect {
            Effect::SendStorageProviderRequest { provider_id, .. } => provider_id.0.clone(),
            _ => panic!("unexpected effect"),
        })
        .collect::<Vec<_>>();

    providers.sort();

    assert_eq!(providers, vec!["sp-1", "sp-2", "sp-3"]);
}

#[test]
fn zero_of_three_successes_does_not_complete() {
    let (mut engine, effects) = start_default();

    for sp in ["sp-1", "sp-2", "sp-3"] {
        let result = engine
            .advance(rejected_reply(sp, request_for(&effects, sp), "digest-a"))
            .expect("rejected replies are recorded");
        assert_eq!(result.status, OperationStatus::Pending);
        assert!(result.effects.is_empty());
    }

    assert_eq!(engine.status(), &OperationStatus::Pending);
}

#[test]
fn one_of_three_successes_does_not_complete() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    assert_eq!(result.status, OperationStatus::Pending);
    assert!(result.effects.is_empty());

    engine
        .advance(rejected_reply(
            "sp-2",
            request_for(&effects, "sp-2"),
            "digest-a",
        ))
        .expect("rejected reply should be recorded");

    engine
        .advance(rejected_reply(
            "sp-3",
            request_for(&effects, "sp-3"),
            "digest-a",
        ))
        .expect("rejected reply should be recorded");

    assert_eq!(engine.status(), &OperationStatus::Pending);
}

#[test]
fn two_of_three_matching_successes_complete_without_waiting_for_third() {
    let (mut engine, effects) = start_default();

    let first = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(reply("sp-2", request_for(&effects, "sp-2"), "digest-a"))
        .expect("second reply should be accepted");

    assert_eq!(
        second.status,
        OperationStatus::Completed {
            committed_digest: "digest-a".to_string(),
            matching_replies: 2,
        }
    );

    assert_eq!(second.effects.len(), 1);
    assert!(matches!(
        second.effects[0],
        Effect::CompleteOperation { .. }
    ));
}

#[test]
fn three_of_three_successes_complete_when_threshold_is_three() {
    let config = OperationConfig {
        operation_id: op_id(),
        storage_providers: vec![provider("sp-1"), provider("sp-2"), provider("sp-3")],
        threshold: 3,
        deadline_ms: None,
    };

    let (mut engine, effects) = OperationEngine::start(config).expect("engine should start");

    engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    engine
        .advance(reply("sp-2", request_for(&effects, "sp-2"), "digest-a"))
        .expect("second reply should be accepted");

    assert_eq!(engine.status(), &OperationStatus::Pending);

    let result = engine
        .advance(reply("sp-3", request_for(&effects, "sp-3"), "digest-a"))
        .expect("third reply should be accepted");

    assert_eq!(
        result.status,
        OperationStatus::Completed {
            committed_digest: "digest-a".to_string(),
            matching_replies: 3,
        }
    );
}

#[test]
fn duplicate_provider_reply_is_rejected() {
    let (mut engine, effects) = start_default();

    engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    let err = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect_err("duplicate reply should fail");

    assert_eq!(err, RecoveryEngineError::DuplicateReply);
}

#[test]
fn unknown_provider_is_rejected() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(reply(
            "sp-unknown",
            RequestId("unknown-request".to_string()),
            "digest-a",
        ))
        .expect_err("unknown provider should fail");

    assert_eq!(err, RecoveryEngineError::UnknownStorageProvider);
}

#[test]
fn unknown_request_is_rejected() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(reply(
            "sp-1",
            RequestId("unknown-request".to_string()),
            "digest-a",
        ))
        .expect_err("unknown request should fail");

    assert_eq!(err, RecoveryEngineError::UnknownRequest);
}

#[test]
fn mismatched_operation_is_rejected() {
    let (mut engine, effects) = start_default();

    let err = engine
        .advance(Event::StorageProviderReply {
            operation_id: OperationId("other-op".to_string()),
            request_id: request_for(&effects, "sp-1"),
            provider_id: provider("sp-1"),
            accepted: true,
            state_digest: "digest-a".to_string(),
        })
        .expect_err("mismatched operation should fail");

    assert_eq!(err, RecoveryEngineError::OperationMismatch);
}

#[test]
fn stale_request_is_rejected() {
    let (mut engine, effects) = start_default();

    let sp1_request = request_for(&effects, "sp-1");

    let err = engine
        .advance(Event::StorageProviderReply {
            operation_id: op_id(),
            request_id: sp1_request,
            provider_id: provider("sp-2"),
            accepted: true,
            state_digest: "digest-a".to_string(),
        })
        .expect_err("request for another provider should be stale");

    assert_eq!(err, RecoveryEngineError::StaleRequest);
}

#[test]
fn reordered_events_are_accepted_when_ids_match() {
    let (mut engine, effects) = start_default();

    let first = engine
        .advance(reply("sp-3", request_for(&effects, "sp-3"), "digest-a"))
        .expect("sp-3 may reply first");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("sp-1 may reply second");

    assert!(matches!(second.status, OperationStatus::Completed { .. }));
}

#[test]
fn mismatched_digest_does_not_complete_threshold() {
    let (mut engine, effects) = start_default();

    engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    let result = engine
        .advance(reply("sp-2", request_for(&effects, "sp-2"), "digest-b"))
        .expect("second reply should be accepted");

    assert_eq!(result.status, OperationStatus::Pending);
    assert!(result.effects.is_empty());
}

#[test]
fn cancellation_marks_operation_and_rejects_future_events() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(Event::Cancel {
            operation_id: op_id(),
        })
        .expect("cancel should work");

    assert_eq!(result.status, OperationStatus::Cancelled);
    assert!(matches!(result.effects[0], Effect::CancelOperation { .. }));

    let err = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect_err("cancelled operation should reject future events");

    assert_eq!(err, RecoveryEngineError::AlreadyCancelled);
}

#[test]
fn timeout_before_deadline_does_not_mark_timed_out() {
    let (mut engine, _) = start_default();

    let result = engine
        .advance(Event::Timeout {
            operation_id: op_id(),
            now_ms: 999,
        })
        .expect("timeout check before deadline should not fail");

    assert_eq!(result.status, OperationStatus::Pending);
    assert!(result.effects.is_empty());
}

#[test]
fn timeout_at_deadline_marks_timed_out() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(Event::Timeout {
            operation_id: op_id(),
            now_ms: 1_000,
        })
        .expect("timeout should work");

    assert_eq!(result.status, OperationStatus::TimedOut);
    assert!(matches!(result.effects[0], Effect::TimeoutOperation { .. }));

    let err = engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect_err("timed-out operation should reject future events");

    assert_eq!(err, RecoveryEngineError::AlreadyTimedOut);
}

#[test]
fn snapshot_contains_recovery_metadata_but_no_protocol_payloads_or_secrets() {
    let (mut engine, effects) = start_default();

    engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("reply should be accepted");

    let snapshot = engine.snapshot();
    let rendered = format!("{snapshot:?}");

    assert!(rendered.contains("op-1"));
    assert!(rendered.contains("sp-1"));
    assert!(rendered.contains("digest-a"));

    assert!(!rendered.contains("password"));
    assert!(!rendered.contains("secret"));
    assert!(!rendered.contains("protocol_payload"));
}

#[test]
fn restore_continues_pending_operation_after_process_death() {
    let (mut engine, effects) = start_default();

    engine
        .advance(reply("sp-1", request_for(&effects, "sp-1"), "digest-a"))
        .expect("first reply should be accepted");

    let snapshot = engine.snapshot();
    let mut restored = OperationEngine::restore(snapshot).expect("snapshot should restore");

    let result = restored
        .advance(reply("sp-2", request_for(&effects, "sp-2"), "digest-a"))
        .expect("restored engine should accept second reply");

    assert_eq!(
        result.status,
        OperationStatus::Completed {
            committed_digest: "digest-a".to_string(),
            matching_replies: 2,
        }
    );
}

#[test]
fn invalid_threshold_is_rejected() {
    let config = OperationConfig {
        operation_id: op_id(),
        storage_providers: vec![provider("sp-1"), provider("sp-2"), provider("sp-3")],
        threshold: 4,
        deadline_ms: None,
    };

    let err = OperationEngine::start(config).expect_err("invalid threshold should fail");

    assert_eq!(err, RecoveryEngineError::InvalidThreshold);
}

#[test]
fn duplicate_storage_provider_config_is_rejected() {
    let config = OperationConfig {
        operation_id: op_id(),
        storage_providers: vec![provider("sp-1"), provider("sp-1"), provider("sp-3")],
        threshold: 2,
        deadline_ms: None,
    };

    let err = OperationEngine::start(config).expect_err("duplicate provider should fail");

    assert_eq!(err, RecoveryEngineError::DuplicateStorageProvider);
}
