#![forbid(unsafe_code)]

//! Core cryptographic and protocol primitives for UpSPA.
//!
//! This crate provides threshold OPRF evaluations, authenticated encryption,
//! protocol flows, and deterministic password encoding.

pub mod aead;
pub mod hash;
pub mod password_encoder;
pub mod protocol;
pub mod sign;
pub mod toprf;
pub mod types;

/// Cryptographic helpers re-exported for convenience.
pub mod crypto {
    pub use crate::aead::{xchacha_decrypt_detached, xchacha_encrypt_detached};
    pub use crate::hash::{hash_suid, hash_to_point, hash_vinfo, oprf_finalize};
    pub use crate::toprf::{
        lagrange_coeffs_at_zero, random_scalar, toprf_client_eval, toprf_client_eval_from_partials,
        toprf_gen, ToprfClient, ToprfClientState, ToprfPartial,
    };
    pub use crate::types::{CtBlob, UpspaError, NONCE_LEN, TAG_LEN};
}

pub use types::UpspaError;
