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
pub enum OperationStatus {
    Pending,
    Completed {
        committed_digest: String,
        matching_replies: usize,
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
        committed_digest: String,
        matching_replies: usize,
    },
    CancelOperation {
        operation_id: OperationId,
    },
    TimeoutOperation {
        operation_id: OperationId,
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
    },
    Timeout {
        operation_id: OperationId,
        now_ms: u64,
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

    pub fn restore(snapshot: OperationSnapshot) -> Result<Self, RecoveryEngineError> {
        let config = OperationConfig {
            operation_id: snapshot.operation_id.clone(),
            storage_providers: snapshot.storage_providers.clone(),
            threshold: snapshot.threshold,
            deadline_ms: snapshot.deadline_ms,
        };

        validate_config(&config)?;

        Ok(Self {
            operation_id: snapshot.operation_id,
            storage_providers: snapshot.storage_providers,
            threshold: snapshot.threshold,
            deadline_ms: snapshot.deadline_ms,
            status: snapshot.status,
            pending_requests: snapshot.pending_requests,
            replies: snapshot.replies,
        })
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

    pub fn advance(&mut self, event: Event) -> Result<AdvanceResult, RecoveryEngineError> {
        match self.status {
            OperationStatus::Pending => {}
            OperationStatus::Completed { .. } => {
                return Err(RecoveryEngineError::AlreadyCompleted);
            }
            OperationStatus::Cancelled => {
                return Err(RecoveryEngineError::AlreadyCancelled);
            }
            OperationStatus::TimedOut => {
                return Err(RecoveryEngineError::AlreadyTimedOut);
            }
        }

        match event {
            Event::StorageProviderReply {
                operation_id,
                request_id,
                provider_id,
                accepted,
                state_digest,
            } => {
                self.ensure_operation(&operation_id)?;
                self.record_provider_reply(provider_id, request_id, accepted, state_digest)?;
                Ok(self.recompute_status())
            }
            Event::Cancel { operation_id } => {
                self.ensure_operation(&operation_id)?;

                self.status = OperationStatus::Cancelled;

                Ok(AdvanceResult {
                    status: self.status.clone(),
                    effects: vec![Effect::CancelOperation {
                        operation_id: self.operation_id.clone(),
                    }],
                })
            }
            Event::Timeout {
                operation_id,
                now_ms,
            } => {
                self.ensure_operation(&operation_id)?;

                if self.deadline_ms.is_some_and(|deadline| now_ms >= deadline) {
                    self.status = OperationStatus::TimedOut;

                    Ok(AdvanceResult {
                        status: self.status.clone(),
                        effects: vec![Effect::TimeoutOperation {
                            operation_id: self.operation_id.clone(),
                        }],
                    })
                } else {
                    Ok(AdvanceResult {
                        status: self.status.clone(),
                        effects: Vec::new(),
                    })
                }
            }
        }
    }

    fn ensure_operation(&self, operation_id: &OperationId) -> Result<(), RecoveryEngineError> {
        if operation_id != &self.operation_id {
            return Err(RecoveryEngineError::OperationMismatch);
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
        let mut digest_counts: BTreeMap<String, usize> = BTreeMap::new();

        for reply in self.replies.values() {
            if reply.accepted {
                *digest_counts.entry(reply.state_digest.clone()).or_insert(0) += 1;
            }
        }

        for (digest, count) in digest_counts {
            if count >= self.threshold {
                self.status = OperationStatus::Completed {
                    committed_digest: digest.clone(),
                    matching_replies: count,
                };

                return AdvanceResult {
                    status: self.status.clone(),
                    effects: vec![Effect::CompleteOperation {
                        operation_id: self.operation_id.clone(),
                        committed_digest: digest,
                        matching_replies: count,
                    }],
                };
            }
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

fn request_id_for(operation_id: &OperationId, provider_id: &StorageProviderId) -> RequestId {
    RequestId(format!("{}:{}:request-v1", operation_id.0, provider_id.0))
}
