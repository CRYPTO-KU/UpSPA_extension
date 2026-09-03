//! Versioned mobile host/engine contract.
//!
//! Every record carries `contract_version` at the envelope level so that a host built against an
//! older contract is rejected with a typed error instead of silently misinterpreting a payload.
//! No type in this module carries a plaintext secret in a `String`.

use zeroize::Zeroize;

/// Version of the mobile host/engine contract, independent of the protocol version.
pub const MOBILE_CONTRACT_VERSION: u32 = 2;

/// Lowest host contract version this engine still accepts.
pub const MIN_SUPPORTED_CONTRACT_VERSION: u32 = 2;

// ---------------------------------------------------------------------------
// Identity, time, and secrets
// ---------------------------------------------------------------------------

/// Opaque correlation handle for one in-flight operation.
///
/// The value is engine-assigned and must be treated as opaque by the host. Hosts never mint one.
#[derive(Clone, Debug, PartialEq, Eq, Hash, uniffi::Record)]
pub struct OperationId {
    pub value: String,
}

impl OperationId {
    pub fn new(value: impl Into<String>) -> Self {
        Self {
            value: value.into(),
        }
    }
}

/// Absolute deadline expressed as milliseconds since the Unix epoch, on the host clock.
///
/// Absolute rather than relative so that a suspended process cannot silently extend a deadline.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, uniffi::Record)]
pub struct Deadline {
    pub epoch_millis: u64,
}

impl Deadline {
    pub fn is_expired_at(&self, now: u64) -> bool {
        now > self.epoch_millis
    }
}

/// A secret value crossing the FFI boundary.
///
/// Deliberately a byte buffer, not a `String`: this keeps the value out of the Kotlin/Java string
/// interning pool, out of `toString()` output, and zeroizable on the Rust side. On the Kotlin side
/// this surfaces as `ByteArray`, which the host is expected to overwrite after use.
///
/// Note the absence of `ZeroizeOnDrop`. A UniFFI record cannot implement `Drop`, because the
/// generated lowering code has to move the fields out of the struct, and Rust forbids moving out of
/// a type with a `Drop` impl (E0509). The type is therefore `Zeroize` but not `ZeroizeOnDrop`, and
/// erasure is the caller's responsibility: every engine path that consumes a secret calls
/// `.zeroize()` on it explicitly. See `engine.rs::submit`.
#[derive(Clone, PartialEq, Eq, Zeroize, uniffi::Record)]
pub struct SecretBytes {
    pub bytes: Vec<u8>,
}

impl SecretBytes {
    pub fn new(bytes: Vec<u8>) -> Self {
        Self { bytes }
    }

    pub fn len(&self) -> usize {
        self.bytes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }
}

/// Redacted on purpose: a secret must never be printable through a derived `Debug`.
impl std::fmt::Debug for SecretBytes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "SecretBytes(<redacted {} bytes>)", self.bytes.len())
    }
}

/// Non-secret site/account selector. Contains no credential material.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AccountSelector {
    pub site_tag: String,
    pub account_label: String,
    pub policy_revision: u32,
}

/// Evidence that the host authenticated the human before the command was issued.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct IdentityEvidence {
    pub subject_tag: String,
    pub authenticated_at_millis: u64,
    /// Opaque attestation bytes from the platform keystore; never a decoded string.
    pub attestation: Vec<u8>,
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

/// What the host asks the engine to do.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CommandBody {
    /// Deterministic no-secret probe used by the lifecycle demo and by host smoke tests.
    Probe { echo_tag: String },
    /// Begin a credential derivation for one account.
    DeriveCredential {
        selector: AccountSelector,
        master_secret: SecretBytes,
    },
    /// Rotate the stored blob for one account.
    RotateBlob {
        selector: AccountSelector,
        evidence: IdentityEvidence,
    },
}

/// Versioned command envelope. The host fills `contract_version` from its generated constant.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct MobileCommand {
    pub contract_version: u32,
    /// Host-chosen idempotency key. Distinct from the engine-assigned `OperationId`.
    pub request_tag: String,
    /// Absolute deadline for the whole operation.
    pub deadline: Deadline,
    pub body: CommandBody,
}

// ---------------------------------------------------------------------------
// Effects
// ---------------------------------------------------------------------------

/// Work the engine hands back to the host. The engine performs no I/O itself.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum EffectBody {
    /// Nothing to do; the host should immediately deliver `HostOutcome::ProbeAck`.
    AckImmediately { echo_tag: String },
    /// Perform one request against the named logical endpoint.
    SendRequest {
        endpoint: String,
        payload: Vec<u8>,
        attempt: u32,
    },
    /// Read a keystore-backed blob.
    ReadSecureBlob { key: String },
    /// Write a keystore-backed blob.
    WriteSecureBlob { key: String, value: SecretBytes },
}

/// Versioned effect envelope, correlated to the operation that produced it.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct MobileEffect {
    pub contract_version: u32,
    pub operation: OperationId,
    pub deadline: Deadline,
    pub body: EffectBody,
}

/// What the host reports back after running an effect.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HostOutcome {
    ProbeAck {
        echo_tag: String,
    },
    RequestSucceeded {
        response: Vec<u8>,
    },
    BlobRead {
        value: SecretBytes,
    },
    BlobWritten,
    HostFailed {
        /// Redacted, non-attributable reason code. Never a raw platform message.
        reason_code: String,
    },
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

/// Terminal or progress notification emitted by the engine.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum EventBody {
    ProbeCompleted { echo_tag: String },
    CredentialReady { handle: String },
    BlobRotated { policy_revision: u32 },
    OperationCancelled { at_millis: u64 },
    OperationFailed { reason_code: String },
}

/// Versioned event envelope.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct MobileEvent {
    pub contract_version: u32,
    pub operation: OperationId,
    /// Monotonically increasing per operation, so a host can discard out-of-order deliveries.
    pub sequence: u32,
    pub body: EventBody,
}

impl MobileEvent {
    /// True only for events that represent successful completion.
    ///
    /// Used by the negative test: no expired, cancelled, or unknown operation may satisfy this.
    pub fn is_success(&self) -> bool {
        matches!(
            self.body,
            EventBody::ProbeCompleted { .. }
                | EventBody::CredentialReady { .. }
                | EventBody::BlobRotated { .. }
        )
    }
}

// ---------------------------------------------------------------------------
// Typed errors
// ---------------------------------------------------------------------------

/// Every failure crossing the boundary is one of these variants. No stringly-typed errors.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum MobileError {
    #[error("host contract version {host} is not supported (engine requires {min}..={current})")]
    UnsupportedContractVersion { host: u32, min: u32, current: u32 },

    #[error("operation {operation} is unknown to this engine instance")]
    UnknownOperation { operation: String },

    #[error("operation {operation} expired at {deadline_millis} (now {now_millis})")]
    OperationExpired {
        operation: String,
        deadline_millis: u64,
        now_millis: u64,
    },

    #[error("operation {operation} was already settled and cannot be reported again")]
    OperationAlreadySettled { operation: String },

    #[error("operation {operation} was cancelled")]
    OperationCancelled { operation: String },

    #[error("outcome does not match the effect issued for operation {operation}")]
    OutcomeMismatch { operation: String },

    #[error("transport failure: {reason_code}")]
    Transport { reason_code: String },

    #[error("secure storage failure: {reason_code}")]
    SecureStorage { reason_code: String },

    #[error("identity evidence rejected: {reason_code}")]
    IdentityRejected { reason_code: String },

    #[error("host callback failed: {reason_code}")]
    HostCallback { reason_code: String },
}

impl From<uniffi::UnexpectedUniFFICallbackError> for MobileError {
    fn from(_: uniffi::UnexpectedUniFFICallbackError) -> Self {
        // Deliberately drops the inner message: host exception text may carry user data.
        MobileError::HostCallback {
            reason_code: "unexpected-host-exception".to_owned(),
        }
    }
}

/// Non-sensitive linkage probe, retained from the bootstrap surface.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BootstrapInfo {
    pub contract_version: u32,
    pub core_crate: String,
    pub core_nonce_length: u32,
    pub implementation_status: String,
}
