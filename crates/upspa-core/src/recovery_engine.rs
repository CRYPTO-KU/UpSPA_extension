use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use thiserror::Error;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct OperationId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct RequestId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct StorageProviderId(pub String);

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationConfig {
    pub operation_id: OperationId,
    pub storage_providers: Vec<StorageProviderId>,
    pub threshold: usize,
    pub deadline_ms: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum OperationFailure {
    QuorumUnavailable,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum OperationStatus {
    Pending,
    Completed {
        committed_digest: String,
        matching_replies: usize,
    },
    Failed {
        reason: OperationFailure,
    },
    Cancelled,
    TimedOut,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Effect {
    SendStorageProviderRequest {
        operation_id: OperationId,
        request_id: RequestId,
        provider_id: StorageProviderId,
    },
    CompleteOperation {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
        committed_digest: String,
        matching_replies: usize,
    },
    FailOperation {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
        reason: OperationFailure,
    },
    CancelOperation {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
    },
    TimeoutOperation {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Event {
    StorageProviderReply {
        operation_id: OperationId,
        request_id: RequestId,
        provider_id: StorageProviderId,
        accepted: bool,
        state_digest: String,
    },
    Cancel {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
    },
    Timeout {
        operation_id: OperationId,
        request_ids: Vec<RequestId>,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderReply {
    pub request_id: RequestId,
    pub accepted: bool,
    pub state_digest: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OperationSnapshot {
    pub operation_id: OperationId,
    pub storage_providers: Vec<StorageProviderId>,
    pub threshold: usize,
    pub deadline_ms: Option<u64>,
    pub status: OperationStatus,
    pub pending_requests: BTreeMap<StorageProviderId, RequestId>,
    pub replies: BTreeMap<StorageProviderId, ProviderReply>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AdvanceResult {
    pub status: OperationStatus,
    pub effects: Vec<Effect>,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum RecoveryEngineError {
    #[error("threshold must be between 1 and number of storage providers")]
    InvalidThreshold,

    #[error("storage provider list must not be empty")]
    EmptyStorageProviderList,

    #[error("storage provider ids must be unique")]
    DuplicateStorageProvider,

    #[error("event belongs to a different operation")]
    OperationMismatch,

    #[error("operation is already completed")]
    AlreadyCompleted,

    #[error("operation has already failed")]
    AlreadyFailed,

    #[error("operation is cancelled")]
    AlreadyCancelled,

    #[error("operation is timed out")]
    AlreadyTimedOut,

    #[error("unknown storage provider")]
    UnknownStorageProvider,

    #[error("unknown request id")]
    UnknownRequest,

    #[error("stale or mismatched request id")]
    StaleRequest,

    #[error("duplicate provider reply")]
    DuplicateReply,

    #[error("request correlation is invalid")]
    RequestCorrelationMismatch,

    #[error("snapshot provider membership is invalid")]
    InvalidSnapshotProviderMembership,

    #[error("snapshot request ids are invalid")]
    InvalidSnapshotRequestIds,

    #[error("snapshot contains an invalid provider reply")]
    InvalidSnapshotReply,

    #[error("snapshot status is inconsistent with recorded replies")]
    InvalidSnapshotStatus,
}
#[derive(Clone, Debug)]
pub struct OperationEngine {
    operation_id: OperationId,
    storage_providers: Vec<StorageProviderId>,
    threshold: usize,
    deadline_ms: Option<u64>,
    status: OperationStatus,
    pending_requests: BTreeMap<StorageProviderId, RequestId>,
    replies: BTreeMap<StorageProviderId, ProviderReply>,
}

impl OperationEngine {
    pub fn start(config: OperationConfig) -> Result<(Self, Vec<Effect>), RecoveryEngineError> {
        validate_config(&config)?;

        let mut pending_requests = BTreeMap::new();
        let mut effects = Vec::new();

        for provider in &config.storage_providers {
            let request_id = request_id_for(&config.operation_id, provider);

            pending_requests.insert(provider.clone(), request_id.clone());

            effects.push(Effect::SendStorageProviderRequest {
                operation_id: config.operation_id.clone(),
                request_id,
                provider_id: provider.clone(),
            });
        }

        let engine = Self {
            operation_id: config.operation_id,
            storage_providers: config.storage_providers,
            threshold: config.threshold,
            deadline_ms: config.deadline_ms,
            status: OperationStatus::Pending,
            pending_requests,
            replies: BTreeMap::new(),
        };

        Ok((engine, effects))
    }

    pub fn restore(
        snapshot: OperationSnapshot,
        now_ms: u64,
    ) -> Result<(Self, Vec<Effect>), RecoveryEngineError> {
        let config = OperationConfig {
            operation_id: snapshot.operation_id.clone(),
            storage_providers: snapshot.storage_providers.clone(),
            threshold: snapshot.threshold,
            deadline_ms: snapshot.deadline_ms,
        };

        validate_config(&config)?;
        validate_snapshot(&snapshot)?;

        let mut engine = Self {
            operation_id: snapshot.operation_id,
            storage_providers: snapshot.storage_providers,
            threshold: snapshot.threshold,
            deadline_ms: snapshot.deadline_ms,
            status: snapshot.status,
            pending_requests: snapshot.pending_requests,
            replies: snapshot.replies,
        };

        if matches!(engine.status, OperationStatus::Pending)
            && engine
                .deadline_ms
                .is_some_and(|deadline| now_ms >= deadline)
        {
            let request_ids = engine.outstanding_request_ids();

            engine.status = OperationStatus::TimedOut;

            return Ok((
                engine,
                vec![Effect::TimeoutOperation {
                    operation_id: config.operation_id,
                    request_ids,
                }],
            ));
        }

        let effects = engine.outstanding_request_effects();

        Ok((engine, effects))
    }

    pub fn snapshot(&self) -> OperationSnapshot {
        OperationSnapshot {
            operation_id: self.operation_id.clone(),
            storage_providers: self.storage_providers.clone(),
            threshold: self.threshold,
            deadline_ms: self.deadline_ms,
            status: self.status.clone(),
            pending_requests: self.pending_requests.clone(),
            replies: self.replies.clone(),
        }
    }

    pub fn status(&self) -> &OperationStatus {
        &self.status
    }

    fn outstanding_request_effects(&self) -> Vec<Effect> {
        if !matches!(self.status, OperationStatus::Pending) {
            return Vec::new();
        }

        let mut effects = Vec::new();

        for provider in &self.storage_providers {
            if self.replies.contains_key(provider) {
                continue;
            }

            if let Some(request_id) = self.pending_requests.get(provider) {
                effects.push(Effect::SendStorageProviderRequest {
                    operation_id: self.operation_id.clone(),
                    request_id: request_id.clone(),
                    provider_id: provider.clone(),
                });
            }
        }

        effects
    }

    pub fn advance(
        &mut self,
        event: Event,
        now_ms: u64,
    ) -> Result<AdvanceResult, RecoveryEngineError> {
        match self.status {
            OperationStatus::Pending => {}
            OperationStatus::Completed { .. } => {
                return Err(RecoveryEngineError::AlreadyCompleted);
            }
            OperationStatus::Failed { .. } => {
                return Err(RecoveryEngineError::AlreadyFailed);
            }
            OperationStatus::Cancelled => {
                return Err(RecoveryEngineError::AlreadyCancelled);
            }
            OperationStatus::TimedOut => {
                return Err(RecoveryEngineError::AlreadyTimedOut);
            }
        }

        let event_operation_id = match &event {
            Event::StorageProviderReply { operation_id, .. } => operation_id,
            Event::Cancel { operation_id, .. } => operation_id,
            Event::Timeout { operation_id, .. } => operation_id,
        };

        self.ensure_operation(event_operation_id)?;

        match &event {
            Event::StorageProviderReply { .. } => {}
            Event::Cancel { request_ids, .. } | Event::Timeout { request_ids, .. } => {
                self.ensure_request_correlation(request_ids)?;
            }
        }

        // Deadline is checked before any state-advancing event is processed.
        if self.deadline_ms.is_some_and(|deadline| now_ms >= deadline) {
            self.status = OperationStatus::TimedOut;

            return Ok(AdvanceResult {
                status: self.status.clone(),
                effects: vec![Effect::TimeoutOperation {
                    operation_id: self.operation_id.clone(),
                    request_ids: self.outstanding_request_ids(),
                }],
            });
        }

        match event {
            Event::StorageProviderReply {
                operation_id: _,
                request_id,
                provider_id,
                accepted,
                state_digest,
            } => {
                self.record_provider_reply(provider_id, request_id, accepted, state_digest)?;
                Ok(self.recompute_status())
            }
            Event::Cancel {
                operation_id: _,
                request_ids: _,
            } => {
                self.status = OperationStatus::Cancelled;

                Ok(AdvanceResult {
                    status: self.status.clone(),
                    effects: vec![Effect::CancelOperation {
                        operation_id: self.operation_id.clone(),
                        request_ids: self.outstanding_request_ids(),
                    }],
                })
            }
            Event::Timeout {
                operation_id: _,
                request_ids: _,
            } => Ok(AdvanceResult {
                status: self.status.clone(),
                effects: Vec::new(),
            }),
        }
    }

    fn ensure_operation(&self, operation_id: &OperationId) -> Result<(), RecoveryEngineError> {
        if operation_id != &self.operation_id {
            return Err(RecoveryEngineError::OperationMismatch);
        }

        Ok(())
    }

    fn all_request_ids(&self) -> Vec<RequestId> {
        self.storage_providers
            .iter()
            .filter_map(|provider| self.pending_requests.get(provider).cloned())
            .collect()
    }

    pub fn outstanding_request_ids(&self) -> Vec<RequestId> {
        self.storage_providers
            .iter()
            .filter_map(|provider| {
                if self.replies.contains_key(provider) {
                    None
                } else {
                    self.pending_requests.get(provider).cloned()
                }
            })
            .collect()
    }

    fn matching_request_ids(&self, digest: &str) -> Vec<RequestId> {
        self.storage_providers
            .iter()
            .filter_map(|provider| {
                let reply = self.replies.get(provider)?;

                if reply.accepted && reply.state_digest.as_str() == digest {
                    Some(reply.request_id.clone())
                } else {
                    None
                }
            })
            .collect()
    }

    fn ensure_request_correlation(
        &self,
        request_ids: &[RequestId],
    ) -> Result<(), RecoveryEngineError> {
        let expected: BTreeSet<_> = self.outstanding_request_ids().into_iter().collect();

        let actual: BTreeSet<_> = request_ids.iter().cloned().collect();

        if actual.len() != request_ids.len() || actual != expected {
            return Err(RecoveryEngineError::RequestCorrelationMismatch);
        }

        Ok(())
    }

    fn record_provider_reply(
        &mut self,
        provider_id: StorageProviderId,
        request_id: RequestId,
        accepted: bool,
        state_digest: String,
    ) -> Result<(), RecoveryEngineError> {
        if !self.storage_providers.contains(&provider_id) {
            return Err(RecoveryEngineError::UnknownStorageProvider);
        }

        if self.replies.contains_key(&provider_id) {
            return Err(RecoveryEngineError::DuplicateReply);
        }

        let expected_request_id = self
            .pending_requests
            .get(&provider_id)
            .ok_or(RecoveryEngineError::UnknownRequest)?;

        if !self
            .pending_requests
            .values()
            .any(|known| known == &request_id)
        {
            return Err(RecoveryEngineError::UnknownRequest);
        }

        if expected_request_id != &request_id {
            return Err(RecoveryEngineError::StaleRequest);
        }

        self.replies.insert(
            provider_id,
            ProviderReply {
                request_id,
                accepted,
                state_digest,
            },
        );

        Ok(())
    }

    fn recompute_status(&mut self) -> AdvanceResult {
        let digest_counts = accepted_digest_counts(&self.replies);

        if let Some((digest, count)) = digest_counts
            .iter()
            .find(|(_, count)| **count >= self.threshold)
        {
            self.status = OperationStatus::Completed {
                committed_digest: digest.clone(),
                matching_replies: *count,
            };

            let request_ids = self.matching_request_ids(digest);

            return AdvanceResult {
                status: self.status.clone(),
                effects: vec![Effect::CompleteOperation {
                    operation_id: self.operation_id.clone(),
                    request_ids,
                    committed_digest: digest.clone(),
                    matching_replies: *count,
                }],
            };
        }

        if quorum_is_unavailable(
            self.storage_providers.len(),
            self.threshold,
            &self.replies,
            &digest_counts,
        ) {
            let reason = OperationFailure::QuorumUnavailable;

            self.status = OperationStatus::Failed {
                reason: reason.clone(),
            };

            return AdvanceResult {
                status: self.status.clone(),
                effects: vec![Effect::FailOperation {
                    operation_id: self.operation_id.clone(),
                    request_ids: self.all_request_ids(),
                    reason,
                }],
            };
        }

        AdvanceResult {
            status: self.status.clone(),
            effects: Vec::new(),
        }
    }
}

fn validate_config(config: &OperationConfig) -> Result<(), RecoveryEngineError> {
    if config.storage_providers.is_empty() {
        return Err(RecoveryEngineError::EmptyStorageProviderList);
    }

    if config.threshold == 0 || config.threshold > config.storage_providers.len() {
        return Err(RecoveryEngineError::InvalidThreshold);
    }

    let unique: BTreeSet<_> = config.storage_providers.iter().collect();

    if unique.len() != config.storage_providers.len() {
        return Err(RecoveryEngineError::DuplicateStorageProvider);
    }

    Ok(())
}

fn accepted_digest_counts(
    replies: &BTreeMap<StorageProviderId, ProviderReply>,
) -> BTreeMap<String, usize> {
    let mut counts = BTreeMap::new();

    for reply in replies.values() {
        if reply.accepted {
            *counts.entry(reply.state_digest.clone()).or_insert(0) += 1;
        }
    }

    counts
}

fn quorum_is_unavailable(
    provider_count: usize,
    threshold: usize,
    replies: &BTreeMap<StorageProviderId, ProviderReply>,
    digest_counts: &BTreeMap<String, usize>,
) -> bool {
    let remaining = provider_count.saturating_sub(replies.len());

    let best_matching_count = digest_counts.values().copied().max().unwrap_or(0);

    best_matching_count + remaining < threshold
}

fn validate_snapshot(snapshot: &OperationSnapshot) -> Result<(), RecoveryEngineError> {
    let configured_providers: BTreeSet<_> = snapshot.storage_providers.iter().cloned().collect();
    let request_providers: BTreeSet<_> = snapshot.pending_requests.keys().cloned().collect();

    // Every configured provider must have exactly one deterministic request id,
    // and no unknown provider may appear in the request map.
    if configured_providers != request_providers {
        return Err(RecoveryEngineError::InvalidSnapshotProviderMembership);
    }

    for provider in &snapshot.storage_providers {
        let expected = request_id_for(&snapshot.operation_id, provider);

        match snapshot.pending_requests.get(provider) {
            Some(actual) if actual == &expected => {}
            _ => return Err(RecoveryEngineError::InvalidSnapshotRequestIds),
        }
    }

    // Every reply must belong to a configured provider and must carry the
    // deterministic request id assigned to that provider.
    for (provider, reply) in &snapshot.replies {
        if !configured_providers.contains(provider) {
            return Err(RecoveryEngineError::InvalidSnapshotReply);
        }

        let expected = snapshot
            .pending_requests
            .get(provider)
            .ok_or(RecoveryEngineError::InvalidSnapshotReply)?;

        if &reply.request_id != expected {
            return Err(RecoveryEngineError::InvalidSnapshotReply);
        }
    }

    let digest_counts = accepted_digest_counts(&snapshot.replies);

    let reached_quorum = digest_counts
        .iter()
        .any(|(_, count)| *count >= snapshot.threshold);

    let quorum_unavailable = quorum_is_unavailable(
        snapshot.storage_providers.len(),
        snapshot.threshold,
        &snapshot.replies,
        &digest_counts,
    );

    match &snapshot.status {
        OperationStatus::Pending => {
            if reached_quorum || quorum_unavailable {
                return Err(RecoveryEngineError::InvalidSnapshotStatus);
            }
        }

        OperationStatus::Completed {
            committed_digest,
            matching_replies,
        } => {
            let actual_count = digest_counts.get(committed_digest).copied().unwrap_or(0);

            if actual_count != snapshot.threshold || *matching_replies != snapshot.threshold {
                return Err(RecoveryEngineError::InvalidSnapshotStatus);
            }
        }

        OperationStatus::Failed {
            reason: OperationFailure::QuorumUnavailable,
        } => {
            if reached_quorum || !quorum_unavailable {
                return Err(RecoveryEngineError::InvalidSnapshotStatus);
            }
        }

        OperationStatus::Cancelled => {
            if reached_quorum || quorum_unavailable {
                return Err(RecoveryEngineError::InvalidSnapshotStatus);
            }
        }

        OperationStatus::TimedOut => {
            if snapshot.deadline_ms.is_none() || reached_quorum || quorum_unavailable {
                return Err(RecoveryEngineError::InvalidSnapshotStatus);
            }
        }
    }

    Ok(())
}

fn request_id_for(operation_id: &OperationId, provider_id: &StorageProviderId) -> RequestId {
    RequestId(format!("{}:{}:request-v1", operation_id.0, provider_id.0))
}
