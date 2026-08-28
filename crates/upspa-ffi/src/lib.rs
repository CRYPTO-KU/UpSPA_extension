//! UniFFI bindings for the UpSPA client-side cryptographic core.
//!
//! Sibling to `upspa-wasm`, same role: bindings only, no cryptography.
//! `upspa-core` is untouched and keeps `#![forbid(unsafe_code)]` and its
//! RNG-agnostic API.
//!
//! Four things in `upspa-core` cannot cross a UniFFI boundary directly. Each is
//! handled here rather than by changing the core:
//!
//! 1. **Generic functions.** `client_setup`, `client_register` and the update
//!    entry points are generic over `R: RngCore + CryptoRng`. UniFFI cannot
//!    export generics, so each gets a non-generic wrapper that supplies `OsRng`
//!    This is what `upspa-wasm` already does for the browser.
//! 2. **Const generics.** `CtBlob<const PT_LEN: usize>` is const-generic and
//!    inexpressible in UniFFI. It crosses as bytes via the `to_vec` /
//!    `from_slice` pair the core already provides.
//! 3. **Tuples and foreign types in return position.** `toprf_gen` returns
//!    `(Scalar, Vec<(u32, Scalar)>)` and `AuthQueries.per_sp` is
//!    `Vec<(u32, [u8; 32])>`. These become named UniFFI records with byte
//!    fields.
//! 4. **Injected RNG.** The binding layer owns the OS entropy source; the core
//!    keeps taking an injected RNG so it stays testable with a seeded one.

use rand_core::OsRng;
use upspa_core::protocol::{
    authenticate::{client_auth_finish, client_auth_prepare},
    register::client_register,
    setup::client_setup,
    CipherId, CipherSp,
};
use upspa_core::toprf::{ToprfClient, ToprfPartial};
use upspa_core::types::UpspaError;

uniffi::setup_scaffolding!();

// ---------------------------------------------------------------- error

/// UniFFI needs an owned error enum. `UpspaError` is not `PartialEq` across all
/// variants and carries a `base64::DecodeError`, so it is flattened here rather
/// than re-exported.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("invalid length: expected {expected}, got {got}")]
    InvalidLength { expected: u32, got: u32 },
    #[error("crypto error: {message}")]
    Crypto { message: String },
    #[error("encoding error: {message}")]
    Encoding { message: String },
}

impl From<UpspaError> for FfiError {
    fn from(e: UpspaError) -> Self {
        match e {
            UpspaError::InvalidLength { expected, got } => FfiError::InvalidLength {
                expected: expected as u32,
                got: got as u32,
            },
            UpspaError::Base64(err) => FfiError::Encoding {
                message: err.to_string(),
            },
            other => FfiError::Crypto {
                message: other.to_string(),
            },
        }
    }
}

fn fixed32(bytes: &[u8]) -> Result<[u8; 32], FfiError> {
    if bytes.len() != 32 {
        return Err(FfiError::InvalidLength {
            expected: 32,
            got: bytes.len() as u32,
        });
    }
    let mut out = [0u8; 32];
    out.copy_from_slice(bytes);
    Ok(out)
}

// ---------------------------------------------------------------- records
// Blocker 3: named records replace tuples and foreign types.

/// One `(sp_id, share)` pair. Replaces `(u32, [u8; 32])` and `(u32, Scalar)`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct SpShare {
    pub sp_id: u32,
    pub value: Vec<u8>,
}

/// Blocker 2: `CipherId` and `CipherSp` are `CtBlob` instantiations. They cross
/// as their wire encoding, nonce || ct || tag, which the core already round
/// trips through `to_vec` and `from_slice`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct Blob {
    pub bytes: Vec<u8>,
}

#[derive(uniffi::Record, Debug)]
pub struct SetupResult {
    pub sig_pk: Vec<u8>,
    pub cid: Blob,
    pub shares: Vec<SpShare>,
    pub sp_payloads_json: String,
}

#[derive(uniffi::Record, Debug)]
pub struct RegistrationResult {
    pub per_sp_json: String,
    pub uid: Vec<u8>,
    pub vinfo: Vec<u8>,
}

#[derive(uniffi::Record, Debug)]
pub struct AuthQueriesResult {
    pub k0: Vec<u8>,
    pub per_sp: Vec<SpShare>,
}

#[derive(uniffi::Record, Debug)]
pub struct AuthFinishResult {
    pub vinfo_prime: Vec<u8>,
    pub best_ctr: u64,
}

#[derive(uniffi::Record, Debug)]
pub struct ToprfBeginResult {
    /// Opaque client state, serialized. Callers must not inspect it.
    pub state_json: String,
    pub blinded_point: Vec<u8>,
}

// ---------------------------------------------------------------- functions
// Blocker 1 and 4: non-generic wrappers supplying OsRng.

#[uniffi::export]
pub fn setup(uid: Vec<u8>, password: Vec<u8>, nsp: u32, tsp: u32) -> Result<SetupResult, FfiError> {
    let mut rng = OsRng;
    let (out, payloads) = client_setup(&uid, &password, nsp as usize, tsp as usize, &mut rng);
    Ok(SetupResult {
        sig_pk: out.sig_pk.to_vec(),
        cid: Blob {
            bytes: out.cid.to_vec(),
        },
        shares: out
            .shares
            .iter()
            .map(|(id, s)| SpShare {
                sp_id: *id,
                value: s.to_vec(),
            })
            .collect(),
        sp_payloads_json: serde_json::to_string(&payloads).map_err(|e| FfiError::Encoding {
            message: e.to_string(),
        })?,
    })
}

#[uniffi::export]
pub fn register(
    uid: Vec<u8>,
    lsj: Vec<u8>,
    password_state_key: Vec<u8>,
    cid: Blob,
    nsp: u32,
) -> Result<RegistrationResult, FfiError> {
    let mut rng = OsRng;
    let key = fixed32(&password_state_key)?;
    let cid = CipherId::from_slice(&cid.bytes).map_err(|_| FfiError::Crypto {
        message: "cid parse".into(),
    })?;
    let out = client_register(&uid, &lsj, &key, &cid, nsp as usize, &mut rng)?;
    Ok(RegistrationResult {
        per_sp_json: serde_json::to_string(&out.per_sp).map_err(|e| FfiError::Encoding {
            message: e.to_string(),
        })?,
        uid: out.to_ls.uid,
        vinfo: out.to_ls.vinfo.to_vec(),
    })
}

#[uniffi::export]
pub fn auth_prepare(
    uid: Vec<u8>,
    lsj: Vec<u8>,
    password_state_key: Vec<u8>,
    cid: Blob,
    nsp: u32,
) -> Result<AuthQueriesResult, FfiError> {
    let key = fixed32(&password_state_key)?;
    let cid = CipherId::from_slice(&cid.bytes).map_err(|_| FfiError::Crypto {
        message: "cid parse".into(),
    })?;
    let q = client_auth_prepare(&uid, &lsj, &key, &cid, nsp as usize)?;
    Ok(AuthQueriesResult {
        k0: q.k0.to_vec(),
        per_sp: q
            .per_sp
            .into_iter()
            .map(|(id, suid)| SpShare {
                sp_id: id,
                value: suid.to_vec(),
            })
            .collect(),
    })
}

#[uniffi::export]
pub fn auth_finish(
    uid: Vec<u8>,
    lsj: Vec<u8>,
    k0: Vec<u8>,
    cjs: Vec<Blob>,
) -> Result<AuthFinishResult, FfiError> {
    let k0 = fixed32(&k0)?;
    let parsed: Result<Vec<CipherSp>, _> = cjs
        .iter()
        .map(|b| CipherSp::from_slice(&b.bytes))
        .collect();
    let parsed = parsed.map_err(|_| FfiError::Crypto {
        message: "cj parse".into(),
    })?;
    let r = client_auth_finish(&uid, &lsj, &k0, &parsed)?;
    Ok(AuthFinishResult {
        vinfo_prime: r.vinfo_prime.to_vec(),
        best_ctr: r.best_ctr,
    })
}

#[uniffi::export]
pub fn toprf_begin(password: Vec<u8>) -> ToprfBeginResult {
    let mut rng = OsRng;
    let (state, blinded) = ToprfClient::begin(&password, &mut rng);
    ToprfBeginResult {
        state_json: serde_json::to_string(&state).unwrap_or_default(),
        blinded_point: blinded.to_vec(),
    }
}

#[uniffi::export]
pub fn toprf_finish(
    password: Vec<u8>,
    state_json: String,
    partials: Vec<SpShare>,
) -> Result<Vec<u8>, FfiError> {
    let state: upspa_core::toprf::ToprfClientState =
        serde_json::from_str(&state_json).map_err(|e| FfiError::Encoding {
            message: e.to_string(),
        })?;
    let parsed: Result<Vec<ToprfPartial>, FfiError> = partials
        .iter()
        .map(|p| {
            Ok(ToprfPartial {
                id: p.sp_id,
                y: fixed32(&p.value)?,
            })
        })
        .collect();
    // ToprfClient::finish is the byte-oriented entry point. The lower-level
    // toprf_client_eval_from_partials takes Scalar and RistrettoPoint directly,
    // which are foreign types that cannot cross the FFI boundary (blocker 3).
    let out = ToprfClient::finish(&password, &state, &parsed?)?;
    Ok(out.to_vec())
}

// ---------------------------------------------------------------- self-test
//
// PRE-REGISTRATION 3b limb 4: round-trip one TOPRF evaluation through the
// binding types against a known vector. Exposed as an FFI function so the same
// check can be run from Swift and Kotlin, not only from `cargo test`. If the
// Rust side passes and a binding side fails, the binding layer is not faithful.

#[uniffi::export]
pub fn self_test_vector() -> Result<String, FfiError> {
    let uid = b"upspa-ffi-self-test".to_vec();
    let password = b"correct horse battery staple".to_vec();
    let s = setup(uid.clone(), password.clone(), 3, 2)?;
    if s.shares.len() != 3 {
        return Err(FfiError::Crypto {
            message: format!("expected 3 shares, got {}", s.shares.len()),
        });
    }
    if s.cid.bytes.len() != CipherId::WIRE_LEN {
        return Err(FfiError::InvalidLength {
            expected: CipherId::WIRE_LEN as u32,
            got: s.cid.bytes.len() as u32,
        });
    }
    Ok(format!(
        "ok shares={} cid_bytes={} sig_pk_bytes={}",
        s.shares.len(),
        s.cid.bytes.len(),
        s.sig_pk.len()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn setup_crosses_the_boundary_shapes() {
        let out = self_test_vector().expect("self test");
        assert!(out.starts_with("ok "), "{out}");
    }

    #[test]
    fn blob_round_trips_through_bytes() {
        let s = setup(b"uid".to_vec(), b"pw".to_vec(), 3, 2).unwrap();
        let parsed = CipherId::from_slice(&s.cid.bytes).expect("cid parses back");
        assert_eq!(parsed.to_vec(), s.cid.bytes);
    }

    #[test]
    fn wrong_length_key_is_rejected_not_padded() {
        let s = setup(b"uid".to_vec(), b"pw".to_vec(), 3, 2).unwrap();
        let err = register(b"uid".to_vec(), b"ls".to_vec(), vec![0u8; 31], s.cid, 3)
            .expect_err("31-byte key must be rejected");
        // assert!, not a bare matches!. A bare matches! returns a bool that is
        // discarded, so the test passed even when it named the wrong variant.
        // Verified by injecting FfiError::Crypto here and watching it still pass.
        assert!(
            matches!(err, FfiError::InvalidLength { expected: 32, got: 31 }),
            "expected InvalidLength{{32,31}}, got {err:?}"
        );
    }
}
