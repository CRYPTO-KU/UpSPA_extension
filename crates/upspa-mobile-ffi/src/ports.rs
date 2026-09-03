//! Native host ports.
//!
//! These traits are exported with `with_foreign`, so UniFFI emits a Kotlin `interface` for each one
//! and (later) a Swift `protocol`. The engine owns no sockets, no keystore handle, and no clock:
//! all of that is the host's, which is what makes the engine deterministically testable.

use std::sync::Arc;

use crate::contract::{IdentityEvidence, MobileError, SecretBytes};

/// Outbound network access. The engine never opens a socket itself.
#[uniffi::export(with_foreign)]
pub trait TransportPort: Send + Sync {
    /// Perform one request against a logical endpoint name (not a URL: routing is host policy).
    fn send(&self, endpoint: String, payload: Vec<u8>) -> Result<Vec<u8>, MobileError>;
}

/// Platform keystore access. Values are byte buffers so nothing lands in the string pool.
#[uniffi::export(with_foreign)]
pub trait SecureStoragePort: Send + Sync {
    fn load(&self, key: String) -> Result<Option<SecretBytes>, MobileError>;
    fn store(&self, key: String, value: SecretBytes) -> Result<(), MobileError>;
    fn remove(&self, key: String) -> Result<(), MobileError>;
}

/// Wall clock, injected so deadline expiry is testable without sleeping.
#[uniffi::export(with_foreign)]
pub trait ClockPort: Send + Sync {
    fn now_epoch_millis(&self) -> u64;
}

/// Proof that the host authenticated the human (BiometricPrompt, device credential, etc.).
#[uniffi::export(with_foreign)]
pub trait IdentityEvidencePort: Send + Sync {
    fn current_evidence(&self) -> Result<IdentityEvidence, MobileError>;
    /// Freshness is a host policy decision; the engine only asks.
    fn is_fresh(&self, evidence: IdentityEvidence, now_epoch_millis: u64) -> bool;
}

/// Diagnostics sink. Contract: the engine only ever passes stable reason codes and
/// non-attributable counters. There is no free-text parameter on purpose.
#[uniffi::export(with_foreign)]
pub trait RedactedDiagnosticsPort: Send + Sync {
    fn record(&self, event_code: String, operation: String, detail_code: String);
}

/// Convenience bundle so the engine constructor stays readable on both sides of the boundary.
pub struct HostPorts {
    pub transport: Arc<dyn TransportPort>,
    pub storage: Arc<dyn SecureStoragePort>,
    pub clock: Arc<dyn ClockPort>,
    pub identity: Arc<dyn IdentityEvidencePort>,
    pub diagnostics: Arc<dyn RedactedDiagnosticsPort>,
}
