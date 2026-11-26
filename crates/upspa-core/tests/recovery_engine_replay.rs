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

// The matrix below extends the same FakeHost/FakeClock harness. It does not
// implement a quorum algorithm: expected outcomes come from finite 2-of-3
// fixture tables, while every restored trace is compared with a separate live run.
const ORDERS: [[usize; 3]; 6] = [
    [0, 1, 2],
    [0, 2, 1],
    [1, 0, 2],
    [1, 2, 0],
    [2, 0, 1],
    [2, 1, 0],
];
const PROVIDERS: [&str; 3] = ["sp-1", "sp-2", "sp-3"];
// R is a rejection; A/B/C are accepted synthetic matching digests.
const PROFILES: [[char; 3]; 10] = [
    ['A', 'A', 'A'],
    ['A', 'A', 'B'],
    ['A', 'B', 'C'],
    ['R', 'A', 'A'],
    ['A', 'R', 'A'],
    ['A', 'A', 'R'],
    ['A', 'R', 'R'],
    ['R', 'A', 'R'],
    ['R', 'R', 'A'],
    ['R', 'R', 'R'],
];

fn completed_a() -> OperationStatus {
    OperationStatus::Completed {
        committed_digest: "A".into(),
        matching_replies: 2,
    }
}

fn failed_quorum() -> OperationStatus {
    OperationStatus::Failed {
        reason: OperationFailure::QuorumUnavailable,
    }
}

fn terminal_error(status: &OperationStatus) -> Option<RecoveryEngineError> {
    match status {
        OperationStatus::Pending => None,
        OperationStatus::Completed { .. } => Some(RecoveryEngineError::AlreadyCompleted),
        OperationStatus::Failed { .. } => Some(RecoveryEngineError::AlreadyFailed),
        OperationStatus::Cancelled => Some(RecoveryEngineError::AlreadyCancelled),
        OperationStatus::TimedOut => Some(RecoveryEngineError::AlreadyTimedOut),
    }
}

#[derive(Clone, Copy, Debug)]
enum Action {
    Reply(usize),
    Duplicate(usize),
    UnknownRequest(usize),
    ReboundRequest(usize),
    WrongOperation(usize),
    BadCancel,
    BadTimeout,
    Cancel,
}

#[derive(Clone, Copy, Debug)]
struct Step {
    at: u64,
    action: Action,
}

#[derive(Debug)]
struct Schedule {
    profile: [char; 3],
    label: String,
    steps: Vec<Step>,
    expected: OperationStatus,
}

fn schedule(profile: [char; 3], order: [usize; 3], boundary: u64, variant: usize) -> Schedule {
    // Variants: plain; duplicate; three invalid reply correlations; invalid
    // cancel/timeout correlation; cancellation at each of the four reply gaps.
    let mut steps = Vec::new();
    let injected = match variant {
        2 => Some(Action::UnknownRequest(order[0])),
        3 => Some(Action::ReboundRequest(order[0])),
        4 => Some(Action::WrongOperation(order[0])),
        5 => Some(Action::BadCancel),
        6 => Some(Action::BadTimeout),
        _ => None,
    };
    if let Some(action) = injected {
        steps.push(Step { at: 100, action });
    }
    for (index, provider) in order.iter().enumerate() {
        let at = if index == 0 { 100 } else { boundary };
        if variant == 7 + index {
            steps.push(Step {
                at,
                action: Action::Cancel,
            });
        }
        steps.push(Step {
            at,
            action: Action::Reply(*provider),
        });
        if index == 0 && variant == 1 {
            steps.push(Step {
                at: 100,
                action: Action::Duplicate(*provider),
            });
        }
    }
    if variant == 10 {
        steps.push(Step {
            at: boundary,
            action: Action::Cancel,
        });
    }

    // Finite fixture oracle, not the implementation's digest-count/quorum rule.
    let pair_terminal = matches!(
        (profile[order[0]], profile[order[1]]),
        ('A', 'A') | ('R', 'R')
    );
    let natural = match profile {
        ['A', 'A', 'A'] | ['A', 'A', 'B'] | ['R', 'A', 'A'] | ['A', 'R', 'A'] | ['A', 'A', 'R'] => {
            completed_a()
        }
        _ => failed_quorum(),
    };
    let expected = if variant == 7 {
        OperationStatus::Cancelled
    } else if boundary >= 1_000 {
        OperationStatus::TimedOut
    } else if variant == 8 || (variant == 9 && !pair_terminal) {
        OperationStatus::Cancelled
    } else {
        natural
    };
    Schedule {
        profile,
        label: format!("profile={profile:?}, order={order:?}, time={boundary}, variant={variant}"),
        steps,
        expected,
    }
}

// A deliberately stale/malformed inbound message is a synthetic fixture, not a
// retained pre-crash host request. Valid pending replies must use the new host.
fn fixture_reply(index: usize, symbol: char) -> Event {
    Event::StorageProviderReply {
        operation_id: op_id(),
        request_id: RequestId(format!("replay-op:{}:request-v1", PROVIDERS[index])),
        provider_id: provider(PROVIDERS[index]),
        accepted: symbol != 'R',
        state_digest: symbol.to_string(),
    }
}

#[derive(Debug, PartialEq, Eq)]
struct Observation {
    result: Result<upspa_core::recovery_engine::AdvanceResult, RecoveryEngineError>,
    snapshot: OperationSnapshot,
    outstanding: Vec<RequestId>,
}

fn drive_step(
    engine: &mut OperationEngine,
    host: &mut FakeHost,
    clock: &mut FakeClock,
    step: Step,
    profile: [char; 3],
) -> Observation {
    let before = engine.snapshot();
    clock.set(step.at);
    let event = match step.action {
        Action::Reply(index) if before.status == OperationStatus::Pending => host.reply(
            PROVIDERS[index],
            profile[index] != 'R',
            &profile[index].to_string(),
        ),
        Action::Reply(index) | Action::Duplicate(index) => fixture_reply(index, profile[index]),
        Action::UnknownRequest(index)
        | Action::ReboundRequest(index)
        | Action::WrongOperation(index) => {
            let mut event = fixture_reply(index, profile[index]);
            if let Event::StorageProviderReply {
                request_id,
                operation_id,
                ..
            } = &mut event
            {
                match step.action {
                    Action::UnknownRequest(_) => *request_id = RequestId("unknown-request".into()),
                    Action::ReboundRequest(_) => {
                        *request_id = RequestId(format!(
                            "replay-op:{}:request-v1",
                            PROVIDERS[(index + 1) % 3]
                        ))
                    }
                    Action::WrongOperation(_) => {
                        *operation_id = OperationId("other-operation".into())
                    }
                    _ => unreachable!(),
                }
            }
            event
        }
        Action::Cancel => Event::Cancel {
            operation_id: op_id(),
            request_ids: host.requests.values().cloned().collect(),
        },
        Action::BadCancel => Event::Cancel {
            operation_id: op_id(),
            request_ids: vec![],
        },
        Action::BadTimeout => Event::Timeout {
            operation_id: op_id(),
            request_ids: vec![],
        },
    };
    let result = engine.advance(event, clock.now());
    let after = engine.snapshot();
    let expected_error = terminal_error(&before.status).or_else(|| match step.action {
        Action::Duplicate(_) => Some(RecoveryEngineError::DuplicateReply),
        Action::UnknownRequest(_) => Some(RecoveryEngineError::UnknownRequest),
        Action::ReboundRequest(_) => Some(RecoveryEngineError::StaleRequest),
        Action::WrongOperation(_) => Some(RecoveryEngineError::OperationMismatch),
        Action::BadCancel | Action::BadTimeout => {
            Some(RecoveryEngineError::RequestCorrelationMismatch)
        }
        _ => None,
    });
    if let Some(error) = expected_error {
        assert_eq!(result, Err(error));
        assert_eq!(
            after, before,
            "rejected events must not change any snapshot field"
        );
    } else {
        let advance = result.as_ref().expect("valid synthetic event");
        assert_eq!(advance.status, after.status);
        if step.at >= 1_000 {
            assert_eq!(after.status, OperationStatus::TimedOut);
            assert_eq!(
                after.replies, before.replies,
                "expired reply must not be recorded"
            );
        }
        if let Action::Reply(index) = step.action {
            if after.status != OperationStatus::TimedOut {
                assert_eq!(after.replies.len(), before.replies.len() + 1);
                host.requests.remove(&provider(PROVIDERS[index]));
            }
        }
        // Check effect correlation independently of restore equivalence.
        let expected_effects = match &after.status {
            OperationStatus::Pending => vec![],
            OperationStatus::Completed {
                committed_digest,
                matching_replies,
            } => {
                assert_eq!(*matching_replies, 2);
                let request_ids = after
                    .replies
                    .values()
                    .filter(|r| r.accepted && r.state_digest == *committed_digest)
                    .map(|r| r.request_id.clone())
                    .collect();
                vec![Effect::CompleteOperation {
                    operation_id: op_id(),
                    request_ids,
                    committed_digest: committed_digest.clone(),
                    matching_replies: 2,
                }]
            }
            OperationStatus::Failed { reason } => vec![Effect::FailOperation {
                operation_id: op_id(),
                request_ids: after.pending_requests.values().cloned().collect(),
                reason: reason.clone(),
            }],
            OperationStatus::Cancelled => vec![Effect::CancelOperation {
                operation_id: op_id(),
                request_ids: host.requests.values().cloned().collect(),
            }],
            OperationStatus::TimedOut => vec![Effect::TimeoutOperation {
                operation_id: op_id(),
                request_ids: host.requests.values().cloned().collect(),
            }],
        };
        assert_eq!(advance.effects, expected_effects);
    }
    Observation {
        result,
        snapshot: after,
        outstanding: engine.outstanding_request_ids(),
    }
}

fn start_replay() -> (OperationEngine, FakeHost, FakeClock) {
    let (engine, effects) = OperationEngine::start(default_config()).unwrap();
    let host = FakeHost::from_effects(op_id(), &effects);
    assert_eq!(host.requests.len(), 3);
    // The original effect vector cannot leak into later restore runs.
    drop(effects);
    (engine, host, FakeClock::new(0))
}

fn serialized_prefix(case: &Schedule, cut: usize) -> (String, u64) {
    let (mut engine, mut host, mut clock) = start_replay();
    for step in &case.steps[..cut] {
        drive_step(&mut engine, &mut host, &mut clock, *step, case.profile);
    }
    let bytes = serde_json::to_string(&engine.snapshot()).unwrap();
    let now = clock.now();
    drop(engine);
    drop(host);
    drop(clock);
    (bytes, now)
}

fn assert_resume_effects(snapshot: &OperationSnapshot, effects: &[Effect]) {
    let expected: Vec<_> = if snapshot.status == OperationStatus::Pending {
        snapshot
            .storage_providers
            .iter()
            .filter(|p| !snapshot.replies.contains_key(*p))
            .map(|p| Effect::SendStorageProviderRequest {
                operation_id: snapshot.operation_id.clone(),
                request_id: snapshot.pending_requests[p].clone(),
                provider_id: p.clone(),
            })
            .collect()
    } else {
        vec![]
    };
    assert_eq!(
        effects, expected,
        "restore must emit exactly the remaining work with stable IDs"
    );
}

fn check_schedule(case: &Schedule) -> usize {
    let (mut live, mut host, mut clock) = start_replay();
    let initial = live.snapshot();
    let trace: Vec<_> = case
        .steps
        .iter()
        .map(|s| drive_step(&mut live, &mut host, &mut clock, *s, case.profile))
        .collect();
    assert_eq!(
        live.status(),
        &case.expected,
        "fixture outcome: {}",
        case.label
    );
    drop(live);
    drop(host);
    drop(clock);

    for cut in 0..=case.steps.len() {
        let (serialized, now) = serialized_prefix(case, cut);
        let snapshot: OperationSnapshot = serde_json::from_str(&serialized).unwrap();
        let expected = if cut == 0 {
            &initial
        } else {
            &trace[cut - 1].snapshot
        };
        assert_eq!(&snapshot, expected);
        let (mut restored, effects) = OperationEngine::restore(snapshot, now).unwrap();
        assert_eq!(
            &restored.snapshot(),
            expected,
            "same-time restore changed state: {case:?}, cut={cut}"
        );
        assert_resume_effects(expected, &effects);
        let mut host = FakeHost::from_effects(op_id(), &effects);
        drop(effects);
        let mut clock = FakeClock::new(now);
        for (offset, step) in case.steps[cut..].iter().enumerate() {
            let observed = drive_step(&mut restored, &mut host, &mut clock, *step, case.profile);
            assert_eq!(
                observed,
                trace[cut + offset],
                "restore divergence: {case:?}, cut={cut}"
            );
        }
        assert_eq!(restored.status(), &case.expected);
        assert_terminal_restore_has_no_effects(&restored, clock.now());
        // Also exercise terminal rejection when the cut was after the final event.
        let before = restored.snapshot();
        assert_eq!(
            restored.advance(fixture_reply(0, 'A'), clock.now()),
            Err(terminal_error(&case.expected).unwrap())
        );
        assert_eq!(restored.snapshot(), before);
    }
    case.steps.len() + 1
}

#[test]
fn replay_scheduling_matrix_restores_equivalently_at_every_boundary() {
    let mut schedules = 0;
    let mut restores = 0;
    for profile in PROFILES {
        for order in ORDERS {
            for boundary in [999, 1_000, 1_001] {
                for variant in 0..11 {
                    let case = schedule(profile, order, boundary, variant);
                    restores += check_schedule(&case);
                    schedules += 1;
                }
            }
        }
    }
    assert_eq!(schedules, 1_980);
    assert_eq!(restores, 9_720);
}

#[test]
fn replay_restore_at_deadline_matches_live_timeout_without_resending() {
    // Every pending prefix of every plain order/profile, restored just before,
    // exactly at, and just after expiry. No cached host/effects cross the restart.
    let mut checks = 0;
    for profile in PROFILES {
        for order in ORDERS {
            let case = schedule(profile, order, 200, 0);
            for cut in 0..=3 {
                let (serialized, _) = serialized_prefix(&case, cut);
                for now in [999, 1_000, 1_001] {
                    let snapshot: OperationSnapshot = serde_json::from_str(&serialized).unwrap();
                    if snapshot.status != OperationStatus::Pending {
                        continue;
                    }
                    let (mut live, mut host, mut clock) = start_replay();
                    for step in &case.steps[..cut] {
                        drive_step(&mut live, &mut host, &mut clock, *step, profile);
                    }
                    let outcome = live
                        .advance(
                            Event::Timeout {
                                operation_id: op_id(),
                                request_ids: live.outstanding_request_ids(),
                            },
                            now,
                        )
                        .unwrap();
                    let (restored, effects) =
                        OperationEngine::restore(snapshot.clone(), now).unwrap();
                    assert_eq!(restored.snapshot(), live.snapshot());
                    assert_eq!(
                        restored.outstanding_request_ids(),
                        live.outstanding_request_ids()
                    );
                    if now == 999 {
                        assert_eq!(outcome.status, OperationStatus::Pending);
                        assert!(outcome.effects.is_empty());
                        assert_resume_effects(&snapshot, &effects);
                    } else {
                        assert_eq!(outcome.status, OperationStatus::TimedOut);
                        assert_eq!(effects, outcome.effects);
                        assert!(matches!(
                            effects.as_slice(),
                            [Effect::TimeoutOperation { .. }]
                        ));
                        assert_terminal_restore_has_no_effects(&restored, now);
                    }
                    checks += 1;
                }
            }
        }
    }
    // 60 initial + 60 one-reply + 34 still-pending two-reply prefixes,
    // each checked at three times. No three-reply prefix remains pending.
    assert_eq!(checks, 462);
}

#[test]
fn replay_exact_deadline_rejects_completion_control() {
    check_schedule(&schedule(['A', 'A', 'A'], ORDERS[0], 1_000, 0));
    check_schedule(&schedule(['A', 'A', 'A'], ORDERS[0], 999, 0));
}

#[test]
fn replay_restore_rejects_rebound_request_control() {
    let case = schedule(['A', 'A', 'A'], ORDERS[0], 999, 0);
    let (serialized, now) = serialized_prefix(&case, 1);
    let valid: OperationSnapshot = serde_json::from_str(&serialized).unwrap();
    let mut forged = valid.clone();
    forged
        .replies
        .get_mut(&provider("sp-1"))
        .unwrap()
        .request_id = valid.pending_requests[&provider("sp-2")].clone();
    assert!(matches!(
        OperationEngine::restore(forged, now),
        Err(RecoveryEngineError::InvalidSnapshotReply)
    ));
    let (_, effects) = OperationEngine::restore(valid.clone(), now).unwrap();
    assert_resume_effects(&valid, &effects);
}
