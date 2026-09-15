// Seeded-POSITIVE fixture for gates/uniffi_secret_fields.py
//
// The same five UniFFI surface shapes as fixtures/negative/uniffi_secret_fields/
// Struct field, enum variant field, standalone export fn, export-impl method,
// export(with_foreign) trait method, but every secret-shaped name here
// is correctly typed as a byte buffer. This must produce ZERO findings.
// "Byte buffers or explicit secret types" are acceptable secret
// representations, so a bare Vec<u8>/Option<Vec<u8>> already satisfies
// the contract on its own; it should not be treated as equivalent to a
// bare String, and ( separately) a gate that only checked shapes 1 and
// 3 would have nothing to say about whether shapes 2/4/5 are byte-buffer
// clean either, since it couldn't see them at all before this fix.

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

#[derive(uniffi::Enum)]
pub enum AllowedUnlockEvent {
    Unlocked { master_secret: Vec<u8>, contract_version: u32 },
    Locked,
}

pub struct AllowedSession;

#[uniffi::export]
impl AllowedSession {
    pub fn ping(&self) -> bool {
        true
    }

    pub fn unlock(&self, master_secret: Vec<u8>) -> bool {
        false
    }
}

#[uniffi::export(with_foreign)]
pub trait AllowedUnlockCallback: Send + Sync {
    fn on_unlock(&self, master_secret: Vec<u8>);
    fn on_ready(&self);
}
