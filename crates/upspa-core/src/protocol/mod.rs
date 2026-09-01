use crate::aead::xchacha_decrypt_detached;
use crate::types::{CtBlob, UpspaError};
use ed25519_dalek::SigningKey;
pub mod authenticate;
pub mod password_update;
pub mod register;
pub mod secret_update;
pub mod setup;
pub const CIPHERID_PT_LEN: usize = 96;
pub const CIPHERSP_PT_LEN: usize = 40;
pub type CipherId = CtBlob<CIPHERID_PT_LEN>;
pub type CipherSp = CtBlob<CIPHERSP_PT_LEN>;
pub fn cipherid_aad(uid: &[u8]) -> Vec<u8> {
    let mut aad = Vec::with_capacity(uid.len() + 9);
    aad.extend_from_slice(uid);
    aad.extend_from_slice(b"|cipherid");
    aad
}
pub fn ciphersp_aad(uid: &[u8]) -> Vec<u8> {
    let mut aad = Vec::with_capacity(uid.len() + 9);
    aad.extend_from_slice(uid);
    aad.extend_from_slice(b"|ciphersp");
    aad
}
#[derive(Clone, Debug)]
pub struct CidPlaintext {
    pub ssk_bytes: [u8; 32],
    pub signing_key: SigningKey,
    pub rsp: [u8; 32],
    pub k0: [u8; 32],
}
impl CidPlaintext {
    pub fn to_bytes(&self) -> [u8; CIPHERID_PT_LEN] {
        let mut pt = [0u8; CIPHERID_PT_LEN];
        pt[0..32].copy_from_slice(&self.ssk_bytes);
        pt[32..64].copy_from_slice(&self.rsp);
        pt[64..96].copy_from_slice(&self.k0);
        pt
    }
}
pub fn parse_cipherid_pt(pt: &[u8; CIPHERID_PT_LEN]) -> CidPlaintext {
    let mut ssk_bytes = [0u8; 32];
    ssk_bytes.copy_from_slice(&pt[0..32]);
    let mut rsp = [0u8; 32];
    rsp.copy_from_slice(&pt[32..64]);
    let mut k0 = [0u8; 32];
    k0.copy_from_slice(&pt[64..96]);
    let signing_key = SigningKey::from_bytes(&ssk_bytes);
    CidPlaintext {
        ssk_bytes,
        signing_key,
        rsp,
        k0,
    }
}
pub fn decrypt_cid(
    uid: &[u8],
    state_key: &[u8; 32],
    cid: &CipherId,
) -> Result<CidPlaintext, UpspaError> {
    let aad = cipherid_aad(uid);
    let pt = xchacha_decrypt_detached(state_key, &aad, cid)?;
    Ok(parse_cipherid_pt(&pt))
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CipherSpPlaintext {
    pub rlsj: [u8; 32],
    pub ctr: u64,
}
pub fn parse_ciphersp_pt(pt: &[u8; CIPHERSP_PT_LEN]) -> CipherSpPlaintext {
    let mut rlsj = [0u8; 32];
    rlsj.copy_from_slice(&pt[0..32]);
    let mut ctr_bytes = [0u8; 8];
    ctr_bytes.copy_from_slice(&pt[32..40]);
    let ctr = u64::from_le_bytes(ctr_bytes);
    CipherSpPlaintext { rlsj, ctr }
}
pub fn decrypt_cj(
    uid: &[u8],
    k0: &[u8; 32],
    cj: &CipherSp,
) -> Result<CipherSpPlaintext, UpspaError> {
    let aad = ciphersp_aad(uid);
    let pt = xchacha_decrypt_detached(k0, &aad, cj)?;
    Ok(parse_ciphersp_pt(&pt))
}
