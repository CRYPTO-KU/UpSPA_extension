//! Deterministic command -> effect -> event engine.
//!
//! The engine is a pure state machine over an operation registry. All time and all I/O arrive
//! through the ports in [`crate::ports`], so a test with a fake clock can drive deadline expiry
//! exactly, with no sleeping and no flakiness.
//!
//! Invariants enforced here:
//! - Expiry is checked before any outcome, on every path that can settle an operation
//!   (`deliver` and `cancel`). An expired operation settles as `OperationExpired`, never as a
//!   success or a cancellation.
//! - Operation IDs and diagnostic correlation IDs are engine-minted and never contain host text.
//!   `MobileCommand::request_tag` is an idempotency key for the host only; it is discarded on entry.
//!   Each ID embeds a per-instance tag, so an event for another engine instance is unknown here.
//! - Nothing the host sends is reflected back: unknown IDs and failure reasons map to fixed codes.
//! - Every secret buffer that crosses the boundary into the engine is moved into a
//!   [`SecretGuard`] before any check runs, so it is erased on success and on every early return.

use std::collections::hash_map::RandomState;
use std::collections::HashMap;
use std::hash::{BuildHasher, Hasher};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use zeroize::Zeroize;

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

/// Owns a secret for as long as the engine holds it and erases it on drop.
///
/// `SecretBytes` itself cannot implement `Drop` (it is a UniFFI record; see `contract.rs`), so
/// the engine moves every incoming secret into this guard as its very first action. Because the
/// erasure lives in `Drop`, it runs on the success path and on every `?` / early `return` alike.
pub(crate) struct SecretGuard(SecretBytes);

impl SecretGuard {
    pub(crate) fn new(secret: SecretBytes) -> Self {
        Self(secret)
    }

    pub(crate) fn expose(&self) -> &SecretBytes {
        &self.0
    }
}

impl Drop for SecretGuard {
    fn drop(&mut self) {
        self.0.zeroize();
        #[cfg(test)]
        erasure_probe::record(self.0.bytes.is_empty());
    }
}

/// Command body after secrets have been moved into guards.
enum GuardedCommandBody {
    Probe {
        echo_tag: String,
    },
    DeriveCredential {
        selector: AccountSelector,
        master_secret: SecretGuard,
    },
    RotateBlob {
        selector: AccountSelector,
        evidence: IdentityEvidence,
    },
}

impl From<CommandBody> for GuardedCommandBody {
    fn from(body: CommandBody) -> Self {
        match body {
            CommandBody::Probe { echo_tag } => Self::Probe { echo_tag },
            CommandBody::DeriveCredential {
                selector,
                master_secret,
            } => Self::DeriveCredential {
                selector,
                master_secret: SecretGuard::new(master_secret),
            },
            CommandBody::RotateBlob { selector, evidence } => {
                Self::RotateBlob { selector, evidence }
            }
        }
    }
}

/// Host outcome after secrets have been moved into guards.
enum GuardedOutcome {
    ProbeAck { echo_tag: String },
    RequestSucceeded { _response: Vec<u8> },
    BlobRead { _value: SecretGuard },
    BlobWritten,
    HostFailed { reason_code: String },
}

impl From<HostOutcome> for GuardedOutcome {
    fn from(outcome: HostOutcome) -> Self {
        match outcome {
            HostOutcome::ProbeAck { echo_tag } => Self::ProbeAck { echo_tag },
            HostOutcome::RequestSucceeded { response } => Self::RequestSucceeded {
                _response: response,
            },
            HostOutcome::BlobRead { value } => Self::BlobRead {
                _value: SecretGuard::new(value),
            },
            HostOutcome::BlobWritten => Self::BlobWritten,
            HostOutcome::HostFailed { reason_code } => Self::HostFailed { reason_code },
        }
    }
}

#[derive(uniffi::Object)]
pub struct MobileEngine {
    ports: HostPorts,
    operations: Mutex<HashMap<String, OperationRecord>>,
    counter: AtomicU64,
    // Per-instance tag embedded in every operation ID (see `fresh_instance_tag`).
    instance: u64,
}

/// The public host contract. Only methods in this block are exported through UniFFI; internal
/// helpers live in the plain `impl` block below and must stay out of the generated bindings
/// (enforced by `scripts/check_mobile_bindings.py`).
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
            instance: fresh_instance_tag(),
        })
    }

    pub fn contract_version(&self) -> u32 {
        MOBILE_CONTRACT_VERSION
    }

    /// Accept a command and return the single effect the host must run next.
    pub fn submit(&self, command: MobileCommand) -> Result<MobileEffect, MobileError> {
        // Take ownership of every field first. Secrets go into guards before any check can
        // return early, and the host's request tag is dropped here: it must never reach an
        // operation ID, a diagnostics record, or an error message.
        let MobileCommand {
            contract_version,
            request_tag: _,
            deadline,
            body,
        } = command;
        let body = GuardedCommandBody::from(body);

        self.check_version(contract_version)?;

        let now = self.ports.clock.now_epoch_millis();
        let operation = self.next_operation_id();

        if deadline.is_expired_at(now) {
            // A command that arrives already expired never becomes an operation. The minted ID
            // is used only for correlation and is never registered, so it cannot be delivered.
            self.ports.diagnostics.record(
                "command.rejected".to_owned(),
                operation.value.clone(),
                "deadline-in-past".to_owned(),
            );
            return Err(MobileError::OperationExpired {
                operation: operation.value,
                deadline_millis: deadline.epoch_millis,
                now_millis: now,
            });
        }

        let (pending, effect_body) = match body {
            GuardedCommandBody::Probe { echo_tag } => (
                PendingKind::Probe {
                    echo_tag: echo_tag.clone(),
                },
                EffectBody::AckImmediately { echo_tag },
            ),
            GuardedCommandBody::DeriveCredential {
                selector,
                master_secret,
            } => {
                if master_secret.expose().is_empty() {
                    return Err(MobileError::IdentityRejected {
                        reason_code: "empty-master-secret".to_owned(),
                    });
                }
                // Only the length reaches the payload; no secret byte is copied into the effect.
                let payload = derivation_request_payload(&selector, master_secret.expose());
                drop(master_secret); // erased here; also erased on every early return above
                (
                    PendingKind::Request,
                    EffectBody::SendRequest {
                        endpoint: "oprf/evaluate".to_owned(),
                        payload,
                        attempt: 1,
                    },
                )
            }
            GuardedCommandBody::RotateBlob { selector, evidence } => {
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
                        slot: format!("blob/{}/{}", selector.site_tag, selector.account_label),
                        value: SecretBytes::new(Vec::new()),
                    },
                )
            }
        };

        self.operations.lock().expect("registry poisoned").insert(
            operation.value.clone(),
            OperationRecord {
                deadline,
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
            deadline,
            body: effect_body,
        })
    }

    /// Report the result of an effect. This is the only path that can produce a success event.
    pub fn deliver(
        &self,
        operation: OperationId,
        outcome: HostOutcome,
    ) -> Result<MobileEvent, MobileError> {
        // Guard any secret in the outcome before a check can return early.
        let outcome = GuardedOutcome::from(outcome);
        let now = self.ports.clock.now_epoch_millis();
        let mut registry = self.operations.lock().expect("registry poisoned");

        // Unknown operation: typed error, never a success event.
        let record = registry
            .get_mut(&operation.value)
            .ok_or_else(unknown_operation)?;

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
            return Err(self.expired(operation, deadline_millis, now));
        }

        let body = match (&record.pending, outcome) {
            (PendingKind::Probe { echo_tag }, GuardedOutcome::ProbeAck { echo_tag: got }) => {
                if *echo_tag != got {
                    return Err(MobileError::OutcomeMismatch {
                        operation: operation.value.clone(),
                    });
                }
                EventBody::ProbeCompleted { echo_tag: got }
            }
            // Derivation is still a placeholder: no credential is derived or validated, so a
            // transport success must not become `CredentialReady`.
            (PendingKind::Request, GuardedOutcome::RequestSucceeded { .. }) => {
                EventBody::OperationFailed {
                    reason_code: "derivation-unimplemented".to_owned(),
                }
            }
            (PendingKind::BlobWrite { policy_revision }, GuardedOutcome::BlobWritten) => {
                EventBody::BlobRotated {
                    policy_revision: *policy_revision,
                }
            }
            (_, GuardedOutcome::HostFailed { reason_code }) => EventBody::OperationFailed {
                reason_code: host_failure_code(&reason_code),
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

    /// Cancel an in-flight operation. Cancellation is terminal.
    ///
    /// The same expiry-before-outcome rule as [`MobileEngine::deliver`] applies: cancelling an
    /// operation whose deadline has passed settles it as expired and returns the typed
    /// `OperationExpired` error, not an `OperationCancelled` event.
    pub fn cancel(&self, operation: OperationId) -> Result<MobileEvent, MobileError> {
        let now = self.ports.clock.now_epoch_millis();
        let mut registry = self.operations.lock().expect("registry poisoned");

        let record = registry
            .get_mut(&operation.value)
            .ok_or_else(unknown_operation)?;

        if record.settled {
            return Err(MobileError::OperationAlreadySettled {
                operation: operation.value.clone(),
            });
        }

        if record.deadline.is_expired_at(now) {
            record.settled = true;
            let deadline_millis = record.deadline.epoch_millis;
            drop(registry);
            return Err(self.expired(operation, deadline_millis, now));
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
}

/// Internal helpers. Deliberately NOT `#[uniffi::export]`: hosts never mint operation IDs and
/// never call the version check directly.
impl MobileEngine {
    /// Number of operations the engine still considers open.
    ///
    /// Rust-side test helper only. It is `pub` because `tests/` is a separate crate, but it is not
    /// part of the UniFFI contract.
    #[doc(hidden)]
    pub fn open_operation_count(&self) -> u32 {
        self.operations
            .lock()
            .expect("registry poisoned")
            .values()
            .filter(|record| !record.settled)
            .count() as u32
    }

    fn check_version(&self, host: u32) -> Result<(), MobileError> {
        if !(MIN_SUPPORTED_CONTRACT_VERSION..=MOBILE_CONTRACT_VERSION).contains(&host) {
            return Err(MobileError::UnsupportedContractVersion {
                host,
                min: MIN_SUPPORTED_CONTRACT_VERSION,
                current: MOBILE_CONTRACT_VERSION,
            });
        }
        Ok(())
    }

    /// Monotonic per instance, bound to this instance, and free of host-supplied text.
    fn next_operation_id(&self) -> OperationId {
        let n = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        OperationId::new(format!("op-{:016x}-{n:06}", self.instance))
    }

    /// Emit the expiry diagnostic and build the typed error. The caller has already settled the
    /// record and released the registry lock.
    fn expired(&self, operation: OperationId, deadline_millis: u64, now: u64) -> MobileError {
        self.ports.diagnostics.record(
            "operation.expired".to_owned(),
            operation.value.clone(),
            "deadline-passed".to_owned(),
        );
        MobileError::OperationExpired {
            operation: operation.value,
            deadline_millis,
            now_millis: now,
        }
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

/// Distinct per engine instance: a random per-process salt plus a per-process counter, so two
/// engines in one process never share a tag and engines in different processes almost never do.
fn fresh_instance_tag() -> u64 {
    static PROCESS_SALT: OnceLock<u64> = OnceLock::new();
    static NEXT_INSTANCE: AtomicU64 = AtomicU64::new(0);
    let salt = *PROCESS_SALT.get_or_init(|| RandomState::new().build_hasher().finish());
    salt.wrapping_add(NEXT_INSTANCE.fetch_add(1, Ordering::SeqCst))
}

/// The host-supplied ID is not echoed: it is host text and may carry user data.
fn unknown_operation() -> MobileError {
    MobileError::UnknownOperation {
        operation: "unrecognized".to_owned(),
    }
}

/// Host failure reasons the engine passes through. Anything else becomes one fixed code, so a host
/// cannot smuggle user data into an event, a log, or debug output; character filtering is not enough.
const HOST_FAILURE_CODES: &[&str] = &[
    "timeout",
    "network-unavailable",
    "server-error",
    "storage-unavailable",
    "authentication-failed",
    "user-cancelled",
];

fn host_failure_code(raw: &str) -> String {
    HOST_FAILURE_CODES
        .iter()
        .find(|code| **code == raw)
        .copied()
        .unwrap_or("host-failure-unclassified")
        .to_owned()
}

/// Test-only observation point for secret erasure. Thread-local so parallel tests don't interfere.
#[cfg(test)]
pub(crate) mod erasure_probe {
    use std::cell::RefCell;

    thread_local! {
        static ERASURES: RefCell<Vec<bool>> = const { RefCell::new(Vec::new()) };
    }

    pub(crate) fn record(erased: bool) {
        ERASURES.with(|e| e.borrow_mut().push(erased));
    }

    /// Returns and clears the erasures observed on this thread (`true` = buffer was emptied).
    pub(crate) fn take() -> Vec<bool> {
        ERASURES.with(|e| std::mem::take(&mut *e.borrow_mut()))
    }
}

#[cfg(test)]
mod tests {
    //! Secret-erasure regressions. These live in-crate because the erasure probe is `cfg(test)`.
    use super::*;
    use crate::fakes::*;

    const T0: u64 = 1_700_000_000_000;

    fn engine(clock: Arc<FakeClock>) -> Arc<MobileEngine> {
        MobileEngine::new(
            Arc::new(FakeTransport::default()),
            Arc::new(FakeSecureStorage::default()),
            clock,
            Arc::new(FakeIdentity::default()),
            Arc::new(RecordingDiagnostics::default()),
        )
    }

    fn derive(version: u32, deadline: u64, secret: &[u8]) -> MobileCommand {
        MobileCommand {
            contract_version: version,
            request_tag: "derive".to_owned(),
            deadline: Deadline {
                epoch_millis: deadline,
            },
            body: CommandBody::DeriveCredential {
                selector: AccountSelector {
                    site_tag: "example.org".to_owned(),
                    account_label: "user".to_owned(),
                    policy_revision: 1,
                },
                master_secret: SecretBytes::new(secret.to_vec()),
            },
        }
    }

    #[test]
    fn secret_erased_on_success() {
        let e = engine(Arc::new(FakeClock::new(T0)));
        erasure_probe::take();
        e.submit(derive(MOBILE_CONTRACT_VERSION, T0 + 5_000, b"s3cret"))
            .expect("accepted");
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn secret_erased_when_contract_version_rejected() {
        let e = engine(Arc::new(FakeClock::new(T0)));
        erasure_probe::take();
        assert!(matches!(
            e.submit(derive(1, T0 + 5_000, b"s3cret")),
            Err(MobileError::UnsupportedContractVersion { .. })
        ));
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn secret_erased_when_deadline_already_passed() {
        let e = engine(Arc::new(FakeClock::new(T0)));
        erasure_probe::take();
        assert!(matches!(
            e.submit(derive(MOBILE_CONTRACT_VERSION, T0 - 1, b"s3cret")),
            Err(MobileError::OperationExpired { .. })
        ));
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn empty_secret_rejection_still_runs_the_guard() {
        let e = engine(Arc::new(FakeClock::new(T0)));
        erasure_probe::take();
        assert!(matches!(
            e.submit(derive(MOBILE_CONTRACT_VERSION, T0 + 5_000, b"")),
            Err(MobileError::IdentityRejected { .. })
        ));
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn secret_in_mismatched_blob_read_outcome_is_erased() {
        let clock = Arc::new(FakeClock::new(T0));
        let e = engine(clock);
        let effect = e
            .submit(MobileCommand {
                contract_version: MOBILE_CONTRACT_VERSION,
                request_tag: "probe".to_owned(),
                deadline: Deadline {
                    epoch_millis: T0 + 5_000,
                },
                body: CommandBody::Probe {
                    echo_tag: "t".to_owned(),
                },
            })
            .expect("accepted");
        erasure_probe::take();
        assert!(matches!(
            e.deliver(
                effect.operation,
                HostOutcome::BlobRead {
                    value: SecretBytes::new(b"blob".to_vec())
                }
            ),
            Err(MobileError::OutcomeMismatch { .. })
        ));
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn secret_in_outcome_for_unknown_operation_is_erased() {
        let e = engine(Arc::new(FakeClock::new(T0)));
        erasure_probe::take();
        assert!(e
            .deliver(
                OperationId::new("op-999999"),
                HostOutcome::BlobRead {
                    value: SecretBytes::new(b"blob".to_vec())
                }
            )
            .is_err());
        assert_eq!(erasure_probe::take(), vec![true]);
    }
}
