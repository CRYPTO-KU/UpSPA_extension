use std::collections::BTreeMap;

use upspa_core::recovery_engine::{
    Effect, Event, OperationConfig, OperationEngine, OperationFailure, OperationId,
    OperationSnapshot, OperationStatus, RecoveryEngineError, RequestId, StorageProviderId,
};

fn op_id() -> OperationId {
    OperationId("replay-op".to_string())
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

#[derive(Clone, Debug)]
struct FakeClock {
    now_ms: u64,
}

impl FakeClock {
    fn new(now_ms: u64) -> Self {
        Self { now_ms }
    }

    fn now(&self) -> u64 {
        self.now_ms
    }

    fn set(&mut self, now_ms: u64) {
        self.now_ms = now_ms;
    }
}

#[derive(Clone, Debug)]
struct FakeHost {
    operation_id: OperationId,
    requests: BTreeMap<StorageProviderId, RequestId>,
}

impl FakeHost {
    fn from_effects(operation_id: OperationId, effects: &[Effect]) -> Self {
        let requests = effects
            .iter()
            .filter_map(|effect| match effect {
                Effect::SendStorageProviderRequest {
                    request_id,
                    provider_id,
                    ..
                } => Some((provider_id.clone(), request_id.clone())),
                _ => None,
            })
            .collect();

        Self {
            operation_id,
            requests,
        }
    }

    fn request_for(&self, provider_id: &str) -> RequestId {
        self.requests
            .get(&provider(provider_id))
            .cloned()
            .expect("fake host should have emitted request")
    }

    fn reply(&self, provider_id: &str, accepted: bool, digest: &str) -> Event {
        Event::StorageProviderReply {
            operation_id: self.operation_id.clone(),
            request_id: self.request_for(provider_id),
            provider_id: provider(provider_id),
            accepted,
            state_digest: digest.to_string(),
        }
    }
}

fn assert_terminal_restore_has_no_effects(engine: &OperationEngine, now_ms: u64) {
    let snapshot = engine.snapshot();

    let (_, effects) =
        OperationEngine::restore(snapshot, now_ms).expect("terminal snapshot should restore");

    assert!(
        effects.is_empty(),
        "terminal snapshots must not resume host effects"
    );
}

#[test]
fn replay_matching_two_of_three_completes_without_waiting_for_third() {
    let (mut engine, effects) =
        OperationEngine::start(default_config()).expect("engine should start");

    let host = FakeHost::from_effects(op_id(), &effects);
    let mut clock = FakeClock::new(100);

    let sp1_request = host.request_for("sp-1");
    let sp2_request = host.request_for("sp-2");

    let first = engine
        .advance(host.reply("sp-1", true, "digest-a"), clock.now())
        .expect("first provider reply should succeed");

    assert_eq!(first.status, OperationStatus::Pending);
    assert!(first.effects.is_empty());

    clock.set(200);

    let second = engine
        .advance(host.reply("sp-2", true, "digest-a"), clock.now())
        .expect("second matching reply should complete");

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

    assert_terminal_restore_has_no_effects(&engine, clock.now());

    let err = engine
        .advance(host.reply("sp-3", true, "digest-a"), clock.now())
        .expect_err("terminal operation must reject later replies");

    assert_eq!(err, RecoveryEngineError::AlreadyCompleted);
}

#[test]
fn replay_delayed_and_duplicate_reply_is_rejected_without_corrupting_state() {
    let (mut engine, effects) =
        OperationEngine::start(default_config()).expect("engine should start");

    let host = FakeHost::from_effects(op_id(), &effects);
    let mut clock = FakeClock::new(100);

    let first = engine
        .advance(host.reply("sp-3", true, "digest-a"), clock.now())
        .expect("first reply should succeed");

    assert_eq!(first.status, OperationStatus::Pending);

    clock.set(700);

    let duplicate = engine
        .advance(host.reply("sp-3", true, "digest-a"), clock.now())
        .expect_err("duplicate provider reply must be rejected");

    assert_eq!(duplicate, RecoveryEngineError::DuplicateReply);
    assert_eq!(engine.status(), &OperationStatus::Pending);

    clock.set(900);

    let result = engine
        .advance(host.reply("sp-1", true, "digest-a"), clock.now())
        .expect("delayed matching reply before deadline should complete");

    assert!(matches!(result.status, OperationStatus::Completed { .. }));

    assert_terminal_restore_has_no_effects(&engine, clock.now());
}

#[test]
fn replay_cancellation_is_terminal_and_produces_no_resume_effects() {
    let (mut engine, effects) =
        OperationEngine::start(default_config()).expect("engine should start");

    let host = FakeHost::from_effects(op_id(), &effects);
    let clock = FakeClock::new(400);

    let outstanding = engine.outstanding_request_ids();

    let result = engine
        .advance(
            Event::Cancel {
                operation_id: op_id(),
                request_ids: outstanding.clone(),
            },
            clock.now(),
        )
        .expect("cancellation should succeed");

    assert_eq!(result.status, OperationStatus::Cancelled);

    assert_eq!(
        result.effects,
        vec![Effect::CancelOperation {
            operation_id: op_id(),
            request_ids: outstanding,
        }]
    );

    assert_terminal_restore_has_no_effects(&engine, clock.now());

    let err = engine
        .advance(host.reply("sp-1", true, "digest-a"), clock.now())
        .expect_err("cancelled operation must reject later replies");

    assert_eq!(err, RecoveryEngineError::AlreadyCancelled);
}

#[test]
fn replay_impossible_quorum_fails_terminally() {
    let (mut engine, effects) =
        OperationEngine::start(default_config()).expect("engine should start");

    let host = FakeHost::from_effects(op_id(), &effects);
    let mut clock = FakeClock::new(100);

    let first = engine
        .advance(host.reply("sp-1", false, "digest-a"), clock.now())
        .expect("first rejection should be recorded");

    assert_eq!(first.status, OperationStatus::Pending);

    clock.set(200);

    let second = engine
        .advance(host.reply("sp-2", false, "digest-a"), clock.now())
        .expect("second rejection should make quorum impossible");

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

    assert_terminal_restore_has_no_effects(&engine, clock.now());

    let err = engine
        .advance(host.reply("sp-3", true, "digest-a"), clock.now())
        .expect_err("failed operation must reject later replies");

    assert_eq!(err, RecoveryEngineError::AlreadyFailed);
}

#[test]
fn replay_process_death_restores_only_from_serialized_snapshot_and_preserves_request_ids() {
    let (mut engine, effects) =
        OperationEngine::start(default_config()).expect("engine should start");

    let host = FakeHost::from_effects(op_id(), &effects);
    let mut clock = FakeClock::new(200);

    engine
        .advance(host.reply("sp-1", true, "digest-a"), clock.now())
        .expect("first provider should succeed");

    let serialized = serde_json::to_string(&engine.snapshot()).expect("snapshot should serialize");

    // Simulate process death. Neither the original engine, host, nor effect
    // vector is available to the restarted process.
    drop(engine);
    drop(host);
    drop(effects);

    clock.set(500);

    let snapshot: OperationSnapshot =
        serde_json::from_str(&serialized).expect("snapshot should deserialize");

    let (mut restored, restored_effects) =
        OperationEngine::restore(snapshot, clock.now()).expect("snapshot should restore");

    let restored_host = FakeHost::from_effects(op_id(), &restored_effects);

    assert_eq!(restored_effects.len(), 2);

    assert_eq!(
        restored_host.request_for("sp-2"),
        RequestId("replay-op:sp-2:request-v1".to_string())
    );

    assert_eq!(
        restored_host.request_for("sp-3"),
        RequestId("replay-op:sp-3:request-v1".to_string())
    );

    assert!(restored_effects
        .iter()
        .all(|effect| matches!(effect, Effect::SendStorageProviderRequest { .. })));

    clock.set(600);

    let completed = restored
        .advance(restored_host.reply("sp-2", true, "digest-a"), clock.now())
        .expect("restored operation should complete");

    assert_eq!(
        completed.status,
        OperationStatus::Completed {
            committed_digest: "digest-a".to_string(),
            matching_replies: 2,
        }
    );

    assert_terminal_restore_has_no_effects(&restored, clock.now());
}
