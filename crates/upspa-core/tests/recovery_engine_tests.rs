use upspa_core::recovery_engine::{
    Effect, Event, OperationConfig, OperationEngine, OperationFailure, OperationId,
    OperationSnapshot, OperationStatus, RecoveryEngineError, RequestId, StorageProviderId,
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

fn request_ids_for(effects: &[Effect]) -> Vec<RequestId> {
    effects
        .iter()
        .filter_map(|effect| match effect {
            Effect::SendStorageProviderRequest { request_id, .. } => Some(request_id.clone()),
            _ => None,
        })
        .collect()
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
fn zero_of_three_successes_fail_when_quorum_becomes_impossible() {
    let (mut engine, effects) = start_default();

    let first = engine
        .advance(
            rejected_reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first rejected reply should be recorded");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(
            rejected_reply("sp-2", request_for(&effects, "sp-2"), "digest-a"),
            500,
        )
        .expect("second rejected reply should make quorum impossible");

    assert_eq!(
        second.status,
        OperationStatus::Failed {
            reason: OperationFailure::QuorumUnavailable,
        }
    );

    assert!(matches!(
        second.effects.as_slice(),
        [Effect::FailOperation {
            reason: OperationFailure::QuorumUnavailable,
            ..
        }]
    ));

    let err = engine
        .advance(
            rejected_reply("sp-3", request_for(&effects, "sp-3"), "digest-a"),
            500,
        )
        .expect_err("terminal failure must reject later replies");

    assert_eq!(err, RecoveryEngineError::AlreadyFailed);
}

#[test]
fn one_of_three_successes_fail_when_remaining_replies_cannot_reach_quorum() {
    let (mut engine, effects) = start_default();

    let first = engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first successful reply should be accepted");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(
            rejected_reply("sp-2", request_for(&effects, "sp-2"), "digest-a"),
            500,
        )
        .expect("second reply should leave quorum possible");

    assert_eq!(second.status, OperationStatus::Pending);

    let third = engine
        .advance(
            rejected_reply("sp-3", request_for(&effects, "sp-3"), "digest-a"),
            500,
        )
        .expect("final rejection should make quorum impossible");

    assert_eq!(
        third.status,
        OperationStatus::Failed {
            reason: OperationFailure::QuorumUnavailable,
        }
    );

    assert!(matches!(
        third.effects.as_slice(),
        [Effect::FailOperation {
            reason: OperationFailure::QuorumUnavailable,
            ..
        }]
    ));
}

#[test]
fn two_of_three_matching_successes_complete_without_waiting_for_third() {
    let (mut engine, effects) = start_default();
    let sp1_request = request_for(&effects, "sp-1");
    let sp2_request = request_for(&effects, "sp-2");

    let first = engine
        .advance(reply("sp-1", sp1_request.clone(), "digest-a"), 500)
        .expect("first reply should be accepted");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(reply("sp-2", sp2_request.clone(), "digest-a"), 500)
        .expect("second reply should be accepted");

    assert_eq!(
        second.status,
        OperationStatus::Completed {
            committed_digest: "digest-a".to_string(),
            matching_replies: 2,
        }
    );

    assert_eq!(
        second.effects,
        vec![Effect::CompleteOperation {
            operation_id: op_id(),
            request_ids: vec![sp1_request, sp2_request],
            committed_digest: "digest-a".to_string(),
            matching_replies: 2,
        }]
    );
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
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should be accepted");

    engine
        .advance(
            reply("sp-2", request_for(&effects, "sp-2"), "digest-a"),
            500,
        )
        .expect("second reply should be accepted");

    assert_eq!(engine.status(), &OperationStatus::Pending);

    let result = engine
        .advance(
            reply("sp-3", request_for(&effects, "sp-3"), "digest-a"),
            500,
        )
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
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should be accepted");

    let err = engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect_err("duplicate reply should fail");

    assert_eq!(err, RecoveryEngineError::DuplicateReply);
}

#[test]
fn unknown_provider_is_rejected() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(
            reply(
                "sp-unknown",
                RequestId("unknown-request".to_string()),
                "digest-a",
            ),
            500,
        )
        .expect_err("unknown provider should fail");

    assert_eq!(err, RecoveryEngineError::UnknownStorageProvider);
}

#[test]
fn unknown_request_is_rejected() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(
            reply("sp-1", RequestId("unknown-request".to_string()), "digest-a"),
            500,
        )
        .expect_err("unknown request should fail");

    assert_eq!(err, RecoveryEngineError::UnknownRequest);
}

#[test]
fn mismatched_operation_is_rejected() {
    let (mut engine, effects) = start_default();

    let err = engine
        .advance(
            Event::StorageProviderReply {
                operation_id: OperationId("other-op".to_string()),
                request_id: request_for(&effects, "sp-1"),
                provider_id: provider("sp-1"),
                accepted: true,
                state_digest: "digest-a".to_string(),
            },
            500,
        )
        .expect_err("mismatched operation should fail");

    assert_eq!(err, RecoveryEngineError::OperationMismatch);
}

#[test]
fn stale_request_is_rejected() {
    let (mut engine, effects) = start_default();

    let sp1_request = request_for(&effects, "sp-1");

    let err = engine
        .advance(
            Event::StorageProviderReply {
                operation_id: op_id(),
                request_id: sp1_request,
                provider_id: provider("sp-2"),
                accepted: true,
                state_digest: "digest-a".to_string(),
            },
            500,
        )
        .expect_err("request for another provider should be stale");

    assert_eq!(err, RecoveryEngineError::StaleRequest);
}

#[test]
fn reordered_events_are_accepted_when_ids_match() {
    let (mut engine, effects) = start_default();

    let first = engine
        .advance(
            reply("sp-3", request_for(&effects, "sp-3"), "digest-a"),
            500,
        )
        .expect("sp-3 may reply first");

    assert_eq!(first.status, OperationStatus::Pending);

    let second = engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("sp-1 may reply second");

    assert!(matches!(second.status, OperationStatus::Completed { .. }));
}

#[test]
fn mismatched_digest_does_not_complete_threshold() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should be accepted");

    let result = engine
        .advance(
            reply("sp-2", request_for(&effects, "sp-2"), "digest-b"),
            500,
        )
        .expect("second reply should be accepted");

    assert_eq!(result.status, OperationStatus::Pending);
    assert!(result.effects.is_empty());
}

#[test]
fn cancellation_marks_operation_and_rejects_future_events() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(
            Event::Cancel {
                operation_id: op_id(),
                request_ids: request_ids_for(&effects),
            },
            500,
        )
        .expect("cancel should work");

    assert_eq!(result.status, OperationStatus::Cancelled);
    assert!(matches!(result.effects[0], Effect::CancelOperation { .. }));

    let err = engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect_err("cancelled operation should reject future events");

    assert_eq!(err, RecoveryEngineError::AlreadyCancelled);
}

#[test]
fn timeout_before_deadline_does_not_mark_timed_out() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(
            Event::Timeout {
                operation_id: op_id(),
                request_ids: request_ids_for(&effects),
            },
            999,
        )
        .expect("timeout check before deadline should not fail");

    assert_eq!(result.status, OperationStatus::Pending);
    assert!(result.effects.is_empty());
}

#[test]
fn timeout_at_deadline_marks_timed_out() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(
            Event::Timeout {
                operation_id: op_id(),
                request_ids: request_ids_for(&effects),
            },
            1_000,
        )
        .expect("timeout should work");

    assert_eq!(result.status, OperationStatus::TimedOut);
    assert!(matches!(result.effects[0], Effect::TimeoutOperation { .. }));

    let err = engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect_err("timed-out operation should reject future events");

    assert_eq!(err, RecoveryEngineError::AlreadyTimedOut);
}

#[test]
fn snapshot_contains_recovery_metadata_but_no_protocol_payloads_or_secrets() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
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

    let original_sp2_request = request_for(&effects, "sp-2");
    let original_sp3_request = request_for(&effects, "sp-3");

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should be accepted");

    let serialized = serde_json::to_string(&engine.snapshot()).expect("snapshot should serialize");

    // Simulate process death: the original engine and emitted effects are gone.
    drop(engine);
    drop(effects);

    let snapshot: OperationSnapshot =
        serde_json::from_str(&serialized).expect("snapshot should deserialize");

    let (mut restored, restored_effects) =
        OperationEngine::restore(snapshot, 500).expect("snapshot should restore");

    assert_eq!(restored_effects.len(), 2);

    let restored_sp2_request = request_for(&restored_effects, "sp-2");
    let restored_sp3_request = request_for(&restored_effects, "sp-3");

    assert_eq!(restored_sp2_request, original_sp2_request);
    assert_eq!(restored_sp3_request, original_sp3_request);

    let result = restored
        .advance(reply("sp-2", restored_sp2_request, "digest-a"), 600)
        .expect("restored engine should accept second matching reply");

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

#[test]
fn restore_rejects_unknown_provider_in_pending_requests() {
    let (engine, _) = start_default();
    let mut snapshot = engine.snapshot();

    snapshot.pending_requests.insert(
        provider("sp-unknown"),
        RequestId("op-1:sp-unknown:request-v1".to_string()),
    );

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("snapshot with unknown provider should be rejected");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotProviderMembership);
}

#[test]
fn restore_rejects_rebound_request_id() {
    let (engine, effects) = start_default();
    let mut snapshot = engine.snapshot();

    let sp2_request = request_for(&effects, "sp-2");

    snapshot
        .pending_requests
        .insert(provider("sp-1"), sp2_request);

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("provider must keep its deterministic request id");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotRequestIds);
}

#[test]
fn restore_rejects_reply_with_wrong_request_id() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("valid reply should be accepted");

    let mut snapshot = engine.snapshot();

    snapshot
        .replies
        .get_mut(&provider("sp-1"))
        .expect("sp-1 reply should exist")
        .request_id = request_for(&effects, "sp-2");

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("reply with another provider's request id should fail");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotReply);
}

#[test]
fn restore_rejects_unknown_provider_reply() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("valid reply should be accepted");

    let mut snapshot = engine.snapshot();

    let existing_reply = snapshot
        .replies
        .remove(&provider("sp-1"))
        .expect("sp-1 reply should exist");

    snapshot
        .replies
        .insert(provider("sp-unknown"), existing_reply);

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("reply from unknown provider should fail");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotReply);
}

#[test]
fn restore_rejects_forged_completed_status() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should be accepted");

    let mut snapshot = engine.snapshot();

    snapshot.status = OperationStatus::Completed {
        committed_digest: "digest-a".to_string(),
        matching_replies: 2,
    };

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("completed status without quorum should fail");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotStatus);
}

#[test]
fn restore_rejects_invalid_threshold() {
    let (engine, _) = start_default();
    let mut snapshot = engine.snapshot();

    snapshot.threshold = 0;

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("invalid snapshot threshold should fail");

    assert_eq!(err, RecoveryEngineError::InvalidThreshold);
}

#[test]
fn late_reply_cannot_complete_expired_operation() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            999,
        )
        .expect("first reply before deadline should be accepted");

    assert_eq!(engine.status(), &OperationStatus::Pending);

    let result = engine
        .advance(
            reply("sp-2", request_for(&effects, "sp-2"), "digest-a"),
            1_000,
        )
        .expect("late reply should trigger expiry");

    assert_eq!(result.status, OperationStatus::TimedOut);

    assert!(matches!(
        result.effects.as_slice(),
        [Effect::TimeoutOperation { .. }]
    ));

    assert_eq!(engine.status(), &OperationStatus::TimedOut);
}

#[test]
fn cancellation_at_deadline_times_out_before_cancelling() {
    let (mut engine, effects) = start_default();

    let result = engine
        .advance(
            Event::Cancel {
                operation_id: op_id(),
                request_ids: request_ids_for(&effects),
            },
            1_000,
        )
        .expect("deadline should be enforced before cancellation");

    assert_eq!(result.status, OperationStatus::TimedOut);

    assert!(matches!(
        result.effects.as_slice(),
        [Effect::TimeoutOperation { .. }]
    ));
}

#[test]
fn cancellation_after_reply_correlates_only_outstanding_requests() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should succeed");

    let expected = vec![request_for(&effects, "sp-2"), request_for(&effects, "sp-3")];

    assert_eq!(engine.outstanding_request_ids(), expected);

    let result = engine
        .advance(
            Event::Cancel {
                operation_id: op_id(),
                request_ids: expected.clone(),
            },
            500,
        )
        .expect("cancel should succeed");

    assert_eq!(
        result.effects,
        vec![Effect::CancelOperation {
            operation_id: op_id(),
            request_ids: expected,
        }]
    );
}

#[test]
fn timeout_after_reply_correlates_only_outstanding_requests() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should succeed");

    let expected = vec![request_for(&effects, "sp-2"), request_for(&effects, "sp-3")];

    let result = engine
        .advance(
            Event::Timeout {
                operation_id: op_id(),
                request_ids: expected.clone(),
            },
            1_000,
        )
        .expect("timeout should succeed");

    assert_eq!(
        result.effects,
        vec![Effect::TimeoutOperation {
            operation_id: op_id(),
            request_ids: expected,
        }]
    );
}

#[test]
fn cancellation_rejects_mismatched_request_correlation() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(
            Event::Cancel {
                operation_id: op_id(),
                request_ids: vec![RequestId("forged-request".to_string())],
            },
            500,
        )
        .expect_err("forged cancellation correlation should fail");

    assert_eq!(err, RecoveryEngineError::RequestCorrelationMismatch);
    assert_eq!(engine.status(), &OperationStatus::Pending);
}

#[test]
fn timeout_rejects_mismatched_request_correlation() {
    let (mut engine, _) = start_default();

    let err = engine
        .advance(
            Event::Timeout {
                operation_id: op_id(),
                request_ids: Vec::new(),
            },
            999,
        )
        .expect_err("missing timeout request correlation should fail");

    assert_eq!(err, RecoveryEngineError::RequestCorrelationMismatch);
    assert_eq!(engine.status(), &OperationStatus::Pending);
}

#[test]
fn restore_rejects_completed_snapshot_with_post_terminal_reply() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            500,
        )
        .expect("first reply should succeed");

    let mut snapshot = engine.snapshot();

    snapshot.replies.insert(
        provider("sp-2"),
        upspa_core::recovery_engine::ProviderReply {
            request_id: request_for(&effects, "sp-2"),
            accepted: true,
            state_digest: "digest-a".to_string(),
        },
    );

    snapshot.replies.insert(
        provider("sp-3"),
        upspa_core::recovery_engine::ProviderReply {
            request_id: request_for(&effects, "sp-3"),
            accepted: true,
            state_digest: "digest-a".to_string(),
        },
    );

    snapshot.status = OperationStatus::Completed {
        committed_digest: "digest-a".to_string(),
        matching_replies: 3,
    };

    let err = OperationEngine::restore(snapshot, 500)
        .expect_err("snapshot containing post-terminal replies must fail");

    assert_eq!(err, RecoveryEngineError::InvalidSnapshotStatus);
}

#[test]
fn restore_after_deadline_times_out_without_reemitting_requests() {
    let (mut engine, effects) = start_default();

    engine
        .advance(
            reply("sp-1", request_for(&effects, "sp-1"), "digest-a"),
            900,
        )
        .expect("reply before deadline should succeed");

    let serialized = serde_json::to_string(&engine.snapshot()).expect("snapshot should serialize");

    drop(engine);
    drop(effects);

    let snapshot: OperationSnapshot =
        serde_json::from_str(&serialized).expect("snapshot should deserialize");

    let (restored, restored_effects) = OperationEngine::restore(snapshot, 1_000)
        .expect("valid expired snapshot should restore as timed out");

    assert_eq!(restored.status(), &OperationStatus::TimedOut);

    assert!(matches!(
        restored_effects.as_slice(),
        [Effect::TimeoutOperation { .. }]
    ));

    assert!(restored_effects
        .iter()
        .all(|effect| !matches!(effect, Effect::SendStorageProviderRequest { .. })));
}
