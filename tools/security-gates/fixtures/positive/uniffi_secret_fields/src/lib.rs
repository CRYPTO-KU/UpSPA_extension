// Seeded-positive fixture for gates/uniffi_secret_fields.py
//
// Every field/parameter here is secret-shaped by NAME, but correctly typed
// as a byte buffer rather than a bare string. Must produce ZERO findings.

#[derive(Clone, Debug, uniffi::Record)]
pub struct AllowedUnlockRequest {
    pub contract_version: u32,
    pub session_secret: Vec<u8>,
    pub rotated_key: Option<Vec<u8>>,
}

#[uniffi::export]
pub fn unlock_with_bytes(master_password: Vec<u8>) -> bool {
    let _ = master_password;
    false
}