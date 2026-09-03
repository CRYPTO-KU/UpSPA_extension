//! Deterministic command -> effect -> event engine.
//!
//! The engine is a pure state machine over an operation registry. All time and all I/O arrive
//! through the ports in [`crate::ports`], so a test with a fake clock can drive deadline expiry
//! exactly, with no sleeping and no flakiness.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use crate::contract::*;
use crate::ports::*;

/// State kept for one in-flight operation.
#[derive(Clone, Debug)]
struct OperationRecord {
    deadline: Deadline,
    sequence: u32,
    settled: bool,
    pending: PendingKind,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum PendingKind {
    Probe { echo_tag: String },
    Request,
    BlobWrite { policy_revision: u32 },
}

#[derive(uniffi::Object)]
pub struct MobileEngine {
    ports: HostPorts,
    operations: Mutex<HashMap<String, OperationRecord>>,
    counter: AtomicU64,
}

#[uniffi::export]
impl MobileEngine {
    /// Build an engine over host-supplied ports. Fake adapters satisfy this signature unchanged.
    #[uniffi::constructor]
    pub fn new(
        transport: Arc<dyn TransportPort>,
        storage: Arc<dyn SecureStoragePort>,
        clock: Arc<dyn ClockPort>,
        identity: Arc<dyn IdentityEvidencePort>,
        diagnostics: Arc<dyn RedactedDiagnosticsPort>,
    ) -> Arc<Self> {
        Arc::new(Self {
            ports: HostPorts {
                transport,
                storage,
                clock,
                identity,
                diagnostics,
            },
            operations: Mutex::new(HashMap::new()),
            counter: AtomicU64::new(0),
        })
    }

    pub fn contract_version(&self) -> u32 {
        MOBILE_CONTRACT_VERSION
    }

    /// Accept a command and return the single effect the host must run next.
    pub fn submit(&self, command: MobileCommand) -> Result<MobileEffect, MobileError> {
        self.check_version(command.contract_version)?;

        let now = self.ports.clock.now_epoch_millis();
        if command.deadline.is_expired_at(now) {
            // A command that arrives already expired never becomes an operation.
            self.ports.diagnostics.record(
                "command.rejected".to_owned(),
                command.request_tag.clone(),
                "deadline-in-past".to_owned(),
            );
            return Err(MobileError::OperationExpired {
                operation: command.request_tag,
                deadline_millis: command.deadline.epoch_millis,
                now_millis: now,
            });
        }

        let operation = self.next_operation_id(&command.request_tag);

        let (pending, body) = match command.body {
            CommandBody::Probe { echo_tag } => (
                PendingKind::Probe {
                    echo_tag: echo_tag.clone(),
                },
                EffectBody::AckImmediately { echo_tag },
            ),
            CommandBody::DeriveCredential {
                selector,
                master_secret,
            } => {
                if master_secret.is_empty() {
                    return Err(MobileError::IdentityRejected {
                        reason_code: "empty-master-secret".to_owned(),
                    });
                }
                // The secret is consumed here and dropped (zeroized) at the end of this scope.
                let payload = derivation_request_payload(&selector, &master_secret);
                (
                    PendingKind::Request,
                    EffectBody::SendRequest {
                        endpoint: "oprf/evaluate".to_owned(),
                        payload,
                        attempt: 1,
                    },
                )
            }
            CommandBody::RotateBlob {
                selector,
                evidence,
            } => {
                if !self.ports.identity.is_fresh(evidence, now) {
                    return Err(MobileError::IdentityRejected {
                        reason_code: "stale-identity-evidence".to_owned(),
                    });
                }
                (
                    PendingKind::BlobWrite {
                        policy_revision: selector.policy_revision,
                    },
                    EffectBody::WriteSecureBlob {
                        key: format!("blob/{}/{}", selector.site_tag, selector.account_label),
                        value: SecretBytes::new(Vec::new()),
                    },
                )
            }
        };

        self.operations.lock().expect("registry poisoned").insert(
            operation.value.clone(),
            OperationRecord {
                deadline: command.deadline,
                sequence: 0,
                settled: false,
                pending,
            },
        );

        self.ports.diagnostics.record(
            "operation.started".to_owned(),
            operation.value.clone(),
            "ok".to_owned(),
        );

        Ok(MobileEffect {
            contract_version: MOBILE_CONTRACT_VERSION,
            operation,
            deadline: command.deadline,
            body,
        })
    }

    /// Report the result of an effect. This is the only path that can produce a success event.
    pub fn deliver(
        &self,
        operation: OperationId,
        outcome: HostOutcome,
    ) -> Result<MobileEvent, MobileError> {
        let now = self.ports.clock.now_epoch_millis();
        let mut registry = self.operations.lock().expect("registry poisoned");

        // Unknown operation: typed error, never a success event.
        let record = registry
            .get_mut(&operation.value)
            .ok_or_else(|| MobileError::UnknownOperation {
                operation: operation.value.clone(),
            })?;

        if record.settled {
            return Err(MobileError::OperationAlreadySettled {
                operation: operation.value.clone(),
            });
        }

        // Stale operation: expiry is checked before the outcome is even inspected, so a host
        // claiming success on an expired operation cannot get a success event out of the engine.
        if record.deadline.is_expired_at(now) {
            record.settled = true;
            let deadline_millis = record.deadline.epoch_millis;
            drop(registry);
            self.ports.diagnostics.record(
                "operation.expired".to_owned(),
                operation.value.clone(),
                "deadline-passed".to_owned(),
            );
            return Err(MobileError::OperationExpired {
                operation: operation.value,
                deadline_millis,
                now_millis: now,
            });
        }

        let body = match (&record.pending, outcome) {
            (PendingKind::Probe { echo_tag }, HostOutcome::ProbeAck { echo_tag: got }) => {
                if *echo_tag != got {
                    return Err(MobileError::OutcomeMismatch {
                        operation: operation.value.clone(),
                    });
                }
                EventBody::ProbeCompleted { echo_tag: got }
            }
            (PendingKind::Request, HostOutcome::RequestSucceeded { response }) => {
                EventBody::CredentialReady {
                    handle: credential_handle(&operation, &response),
                }
            }
            (PendingKind::BlobWrite { policy_revision }, HostOutcome::BlobWritten) => {
                EventBody::BlobRotated {
                    policy_revision: *policy_revision,
                }
            }
            (_, HostOutcome::HostFailed { reason_code }) => EventBody::OperationFailed {
                reason_code: sanitize_reason(&reason_code),
            },
            _ => {
                return Err(MobileError::OutcomeMismatch {
                    operation: operation.value.clone(),
                })
            }
        };

        record.settled = true;
        record.sequence += 1;
        let sequence = record.sequence;
        drop(registry);

        self.ports.diagnostics.record(
            "operation.settled".to_owned(),
            operation.value.clone(),
            "ok".to_owned(),
        );

        Ok(MobileEvent {
            contract_version: MOBILE_CONTRACT_VERSION,
            operation,
            sequence,
            body,
        })
    }

    /// Cancel an in-flight operation. Cancellation is terminal and idempotent-safe.
    pub fn cancel(&self, operation: OperationId) -> Result<MobileEvent, MobileError> {
        let now = self.ports.clock.now_epoch_millis();
        let mut registry = self.operations.lock().expect("registry poisoned");

        let record = registry
            .get_mut(&operation.value)
            .ok_or_else(|| MobileError::UnknownOperation {
                operation: operation.value.clone(),
            })?;

        if record.settled {
            return Err(MobileError::OperationAlreadySettled {
                operation: operation.value.clone(),
            });
        }

        record.settled = true;
        record.sequence += 1;
        let sequence = record.sequence;
        drop(registry);

        self.ports.diagnostics.record(
            "operation.cancelled".to_owned(),
            operation.value.clone(),
            "host-request".to_owned(),
        );

        Ok(MobileEvent {
            contract_version: MOBILE_CONTRACT_VERSION,
            operation,
            sequence,
            body: EventBody::OperationCancelled { at_millis: now },
        })
    }

    /// Number of operations the engine still considers open. Test and diagnostics helper.
    pub fn open_operation_count(&self) -> u32 {
        self.operations
            .lock()
            .expect("registry poisoned")
            .values()
            .filter(|record| !record.settled)
            .count() as u32
    }

    fn check_version(&self, host: u32) -> Result<(), MobileError> {
        if host < MIN_SUPPORTED_CONTRACT_VERSION || host > MOBILE_CONTRACT_VERSION {
            return Err(MobileError::UnsupportedContractVersion {
                host,
                min: MIN_SUPPORTED_CONTRACT_VERSION,
                current: MOBILE_CONTRACT_VERSION,
            });
        }
        Ok(())
    }

    fn next_operation_id(&self, request_tag: &str) -> OperationId {
        // Deterministic and monotonic: the demo lifecycle produces the same IDs on every run.
        let n = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        OperationId::new(format!("op-{n:06}-{request_tag}"))
    }
}

/// Placeholder derivation payload. Length-only, so no secret bytes are copied into the effect.
fn derivation_request_payload(selector: &AccountSelector, master_secret: &SecretBytes) -> Vec<u8> {
    let mut payload = Vec::new();
    payload.extend_from_slice(selector.site_tag.as_bytes());
    payload.push(0x1f);
    payload.extend_from_slice(selector.account_label.as_bytes());
    payload.push(0x1f);
    payload.extend_from_slice(&(master_secret.len() as u32).to_be_bytes());
    payload
}

fn credential_handle(operation: &OperationId, response: &[u8]) -> String {
    format!("handle:{}:{}", operation.value, response.len())
}

/// Reason codes are constrained to a safe alphabet so a host cannot smuggle user data into logs.
fn sanitize_reason(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .take(48)
        .collect();
    if cleaned.is_empty() {
        "unspecified".to_owned()
    } else {
        cleaned
    }
}
