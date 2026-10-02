use crate::types::{CtBlob, UpspaError, NONCE_LEN, TAG_LEN};
use chacha20poly1305::{
    aead::{generic_array::GenericArray, AeadInPlace, Error as AeadError, KeyInit},
    XChaCha20Poly1305, XNonce,
};
use rand_core::RngCore;
pub fn xchacha_encrypt_detached<const PT_LEN: usize>(
    key: &[u8; 32],
    aad: &[u8],
    plaintext: &[u8; PT_LEN],
    rng: &mut impl RngCore,
) -> CtBlob<PT_LEN> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).unwrap();
    let mut nonce = [0u8; NONCE_LEN];
    rng.fill_bytes(&mut nonce);
    let xnonce = XNonce::from_slice(&nonce);
    let mut ct = *plaintext;
    let tag = cipher
        .encrypt_in_place_detached(xnonce, aad, &mut ct)
        .expect("XChaCha20-Poly1305 encryption should not fail for in-memory buffers");
    let mut tag_bytes = [0u8; TAG_LEN];
    tag_bytes.copy_from_slice(tag.as_slice());
    CtBlob {
        nonce,
        ct,
        tag: tag_bytes,
    }
}
pub fn xchacha_decrypt_detached<const PT_LEN: usize>(
    key: &[u8; 32],
    aad: &[u8],
    blob: &CtBlob<PT_LEN>,
) -> Result<[u8; PT_LEN], AeadError> {
    let cipher = XChaCha20Poly1305::new_from_slice(key).unwrap();
    let xnonce = XNonce::from_slice(&blob.nonce);
    let mut pt = blob.ct;
    let tag = GenericArray::from_slice(&blob.tag);
    cipher.decrypt_in_place_detached(xnonce, aad, &mut pt, tag)?;
    Ok(pt)
}
impl From<AeadError> for UpspaError {
    fn from(_: AeadError) -> Self {
        UpspaError::Aead
    }
}
