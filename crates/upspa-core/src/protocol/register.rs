use crate::aead::xchacha_encrypt_detached;
use crate::hash::{hash_suid, hash_vinfo};
use crate::protocol::{ciphersp_aad, decrypt_cid, CipherId, CipherSp, CIPHERSP_PT_LEN};
use crate::types::UpspaError;
use rand_core::{CryptoRng, RngCore};
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RegistrationSpMessage {
    pub sp_id: u32,
    pub suid: [u8; 32],
    pub cj: CipherSp,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RegistrationLsMessage {
    pub uid: Vec<u8>,
    pub vinfo: [u8; 32],
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RegistrationOutput {
    pub per_sp: Vec<RegistrationSpMessage>,
    pub to_ls: RegistrationLsMessage,
}
pub fn client_register<R: RngCore + CryptoRng>(
    uid: &[u8],
    lsj: &[u8],
    password_state_key: &[u8; 32],
    cid: &CipherId,
    nsp: usize,
    rng: &mut R,
) -> Result<RegistrationOutput, UpspaError> {
    let cid_pt = decrypt_cid(uid, password_state_key, cid)?;
    let rsp = cid_pt.rsp;
    let k0 = cid_pt.k0;
    let mut per_sp = Vec::with_capacity(nsp);
    let mut rlsj = [0u8; 32];
    rng.fill_bytes(&mut rlsj);
    let ctr: u64 = 0;
    let mut ciphersp_pt = [0u8; CIPHERSP_PT_LEN];
    ciphersp_pt[0..32].copy_from_slice(&rlsj);
    ciphersp_pt[32..40].copy_from_slice(&ctr.to_le_bytes());
    let aad = ciphersp_aad(uid);
    let cj = xchacha_encrypt_detached(&k0, &aad, &ciphersp_pt, rng);
    let vinfo = hash_vinfo(&rlsj, lsj);
    for i in 1..=nsp {
        let suid = hash_suid(&rsp, lsj, i as u32);
        per_sp.push(RegistrationSpMessage {
            sp_id: i as u32,
            suid,
            cj: cj.clone(),
        });
    }
    let to_ls = RegistrationLsMessage {
        uid: uid.to_vec(),
        vinfo,
    };
    Ok(RegistrationOutput { per_sp, to_ls })
}
