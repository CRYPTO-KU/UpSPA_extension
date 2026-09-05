// Seeded-negative fixture for gates/uniffi_secret_fields.py
//
// The ONLY intended failure here: `master_password` is a secret-shaped
// field name (matches the SECRET_NAME pattern) typed as a bare `String`
// with no protective wrapper type, exactly what "represent secret values
// as byte buffers or explicit secret types, not ordinary strings"
// is asking to avoid.
//
// `contract_version` and `request_id` are present specifically to prove
// the gate does NOT flag ordinary, non-secret-shaped fields; if it did,
// that would be a false-positive bug in the gate, not a real finding.

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BadUnlockRequest {
    pub contract_version: u32,
    pub request_id: String,
    pub master_password: String,
    pub state_key: String,
}

#[uniffi::export]
pub fn unlock_with_password(master_password: String, request_id: String) -> bool {
    let _ = (master_password, request_id);
    false
}
