use crate::types::UpspaError;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
pub const ED25519_PK_LEN: usize = 32;
pub const ED25519_SIG_LEN: usize = 64;
pub fn sign_detached(signing_key: &SigningKey, msg: &[u8]) -> [u8; ED25519_SIG_LEN] {
    let sig: Signature = signing_key.sign(msg);
    sig.to_bytes()
}
pub fn verify_detached(
    verifying_key_bytes: &[u8; ED25519_PK_LEN],
    msg: &[u8],
    sig_bytes: &[u8; ED25519_SIG_LEN],
) -> Result<(), UpspaError> {
    let vk = VerifyingKey::from_bytes(verifying_key_bytes).map_err(|_| UpspaError::Signature)?;
    let sig = Signature::from_bytes(sig_bytes);
    vk.verify(msg, &sig).map_err(|_| UpspaError::Signature)
}
pub fn signing_key_from_bytes(bytes: &[u8; 32]) -> SigningKey {
    SigningKey::from_bytes(bytes)
}
