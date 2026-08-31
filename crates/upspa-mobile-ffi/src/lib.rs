#![forbid(unsafe_code)]

//! Bootstrap boundary for native UpSPA clients.
//!
//! The first template intentionally exports only non-sensitive build information. Protocol
//! operations will be added as versioned command/event/effect records after the compatibility
//! profile for this repository is generated and reviewed.

/// Version of the mobile host/engine contract, independent of the protocol version.
pub const MOBILE_CONTRACT_VERSION: u32 = 1;

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BootstrapInfo {
    pub contract_version: u32,
    pub core_crate: String,
    pub core_nonce_length: u32,
    pub implementation_status: String,
}

/// Non-sensitive linkage probe used by Android/iOS template hosts.
///
/// Referencing a constant from `upspa-core` ensures this boundary is linked to the canonical core
/// in this repository without exposing or invoking a cryptographic operation prematurely.
#[uniffi::export]
pub fn bootstrap_info() -> BootstrapInfo {
    BootstrapInfo {
        contract_version: MOBILE_CONTRACT_VERSION,
        core_crate: "upspa-core".to_owned(),
        core_nonce_length: upspa_core::types::NONCE_LEN as u32,
        implementation_status: "template-only-no-secret-operations".to_owned(),
    }
}

uniffi::setup_scaffolding!();

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bootstrap_info_identifies_the_local_core() {
        let info = bootstrap_info();
        assert_eq!(info.contract_version, 1);
        assert_eq!(info.core_crate, "upspa-core");
        assert_eq!(info.core_nonce_length, 24);
    }
}
