use crate::aead::xchacha_encrypt_detached;
use crate::hash::{hash_suid, hash_vinfo};
use crate::protocol::{ciphersp_aad, decrypt_cid, decrypt_cj, CipherId, CipherSp, CIPHERSP_PT_LEN};
use crate::types::UpspaError;
use rand_core::{CryptoRng, RngCore};
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SecretUpdateQueries {
    pub k0: [u8; 32],
    pub per_sp: Vec<(u32, [u8; 32])>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SecretUpdateOutput {
    pub vinfo_prime: [u8; 32],
    pub vinfo_new: [u8; 32],
    pub cj_new: CipherSp,
    pub old_ctr: u64,
    pub new_ctr: u64,
}
pub fn client_secret_update_prepare(
    uid: &[u8],
    lsj: &[u8],
    password_state_key: &[u8; 32],
    cid: &CipherId,
    nsp: usize,
) -> Result<SecretUpdateQueries, UpspaError> {
    let cid_pt = decrypt_cid(uid, password_state_key, cid)?;
    let rsp = cid_pt.rsp;
    let k0 = cid_pt.k0;
    let mut per_sp = Vec::with_capacity(nsp);
    for i in 1..=nsp {
        let suid = hash_suid(&rsp, lsj, i as u32);
        per_sp.push((i as u32, suid));
    }
    Ok(SecretUpdateQueries { k0, per_sp })
}
pub fn client_secret_update_finish<R: RngCore + CryptoRng>(
    uid: &[u8],
    lsj: &[u8],
    k0: &[u8; 32],
    cjs: &[CipherSp],
    rng: &mut R,
) -> Result<SecretUpdateOutput, UpspaError> {
    if cjs.is_empty() {
        return Err(UpspaError::InvalidLength {
            expected: 1,
            got: 0,
        });
    }
    let mut old_ctr: u64 = 0;
    let mut old_rlsj = [0u8; 32];
    let mut any_ok = false;
    for cj in cjs {
        let pt = decrypt_cj(uid, k0, cj)?;
        any_ok = true;
        if pt.ctr >= old_ctr {
            old_ctr = pt.ctr;
            old_rlsj = pt.rlsj;
        }
    }
    if !any_ok {
        return Err(UpspaError::Aead);
    }
    let vinfo_prime = hash_vinfo(&old_rlsj, lsj);
    let mut new_rlsj = [0u8; 32];
    rng.fill_bytes(&mut new_rlsj);
    let new_ctr = old_ctr.wrapping_add(1);
    let mut pt = [0u8; CIPHERSP_PT_LEN];
    pt[0..32].copy_from_slice(&new_rlsj);
    pt[32..40].copy_from_slice(&new_ctr.to_le_bytes());
    let aad = ciphersp_aad(uid);
    let cj_new = xchacha_encrypt_detached(k0, &aad, &pt, rng);
    let vinfo_new = hash_vinfo(&new_rlsj, lsj);
    Ok(SecretUpdateOutput {
        vinfo_prime,
        vinfo_new,
        cj_new,
        old_ctr,
        new_ctr,
    })
}
