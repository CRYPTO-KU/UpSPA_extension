#![forbid(unsafe_code)]

//! Versioned UniFFI boundary for native UpSPA clients.
//!
//! Layout:
//! - [`contract`] — versioned commands, effects, events, typed errors, operation IDs, deadlines.
//! - [`ports`] — host-implemented traits (transport, secure storage, clock, identity, diagnostics).
//! - [`engine`] — the deterministic command -> effect -> event state machine.
//! - [`fakes`] — fake adapters only; nothing here performs real I/O.

pub mod contract;
pub mod engine;
pub mod fakes;
pub mod ports;

pub use contract::*;
pub use engine::MobileEngine;
pub use ports::*;

/// Non-sensitive linkage probe used by Android/iOS hosts to confirm they are bound to this core.
#[uniffi::export]
pub fn bootstrap_info() -> BootstrapInfo {
    BootstrapInfo {
        contract_version: MOBILE_CONTRACT_VERSION,
        core_crate: "upspa-core".to_owned(),
        core_nonce_length: upspa_core::types::NONCE_LEN as u32,
        implementation_status: "versioned-contract-fake-adapters-only".to_owned(),
    }
}

/// Constant the host compares against its own generated value before issuing any command.
#[uniffi::export]
pub fn mobile_contract_version() -> u32 {
    MOBILE_CONTRACT_VERSION
}

uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bootstrap_info_identifies_the_local_core() {
        let info = bootstrap_info();
        assert_eq!(info.contract_version, MOBILE_CONTRACT_VERSION);
        assert_eq!(info.core_crate, "upspa-core");
        assert_eq!(info.core_nonce_length, 24);
    }

    #[test]
    fn secret_debug_is_redacted() {
        let secret = SecretBytes::new(b"correct horse battery".to_vec());
        let rendered = format!("{secret:?}");
        assert!(rendered.contains("redacted"));
        assert!(!rendered.contains("horse"));
    }
}
