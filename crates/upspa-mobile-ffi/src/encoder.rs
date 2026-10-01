//! Minimal FFI facade over the canonical Rust password encoder in `upspa-core`.
//!
//! This module does not implement any encoding logic. It only:
//! - accepts the secret as `SecretBytes` (never a `String`) and erases it on every exit path;
//! - accepts only fully normalized policies, because the Rust/TypeScript parity contract covers
//!   normalized policies only and the two implementations normalize raw policies differently;
//! - returns the password as `SecretBytes`, so the host receives a `ByteArray` it can overwrite
//!   rather than an immutable, internable JVM `String`.
//!
//! Known limit, outside this facade: `upspa_core::password_encoder` builds intermediate seed
//! `String`s internally that it does not zeroize.

use upspa_core::password_encoder::{
    encode_secret_as_password, is_normalized_policy, PasswordEncoderError, PasswordPolicy,
};

use crate::contract::{MobileError, SecretBytes};
use crate::engine::SecretGuard;

/// Password policy as it crosses the boundary. Must already be in normalized form.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct NormalizedPasswordPolicy {
    pub min_len: u32,
    pub max_len: u32,
    pub require_upper: bool,
    pub require_lower: bool,
    pub require_digit: bool,
    pub require_symbol: bool,
    pub allowed_symbols: String,
    pub forbid_whitespace: bool,
    pub forbidden_substrings: Vec<String>,
}

impl From<NormalizedPasswordPolicy> for PasswordPolicy {
    fn from(p: NormalizedPasswordPolicy) -> Self {
        PasswordPolicy {
            min_len: p.min_len,
            max_len: p.max_len,
            require_upper: p.require_upper,
            require_lower: p.require_lower,
            require_digit: p.require_digit,
            require_symbol: p.require_symbol,
            allowed_symbols: p.allowed_symbols,
            forbid_whitespace: p.forbid_whitespace,
            forbidden_substrings: p.forbidden_substrings,
        }
    }
}

fn encoding_error(reason_code: &str) -> MobileError {
    MobileError::PasswordEncoding {
        reason_code: reason_code.to_owned(),
    }
}

/// Encode `secret` into a site password with the canonical `upspa-password-encoding-v2` encoder.
///
/// Errors are typed `MobileError::PasswordEncoding` with fixed reason codes; no input value is
/// ever echoed into an error.
#[uniffi::export]
pub fn encode_password(
    secret: SecretBytes,
    policy: NormalizedPasswordPolicy,
    account_id: String,
    counter: u32,
) -> Result<SecretBytes, MobileError> {
    // Guard first: erased on success and on every early return below.
    let secret = SecretGuard::new(secret);

    let policy = PasswordPolicy::from(policy);
    if !is_normalized_policy(&policy) {
        return Err(encoding_error("policy-not-normalized"));
    }

    // Borrowed view, no copy of the secret bytes.
    let secret_text = std::str::from_utf8(&secret.expose().bytes)
        .map_err(|_| encoding_error("secret-not-utf8"))?;

    match encode_secret_as_password(secret_text, &policy, &account_id, counter) {
        // `into_bytes` moves the buffer; the password is never copied into a second allocation.
        Ok(password) => Ok(SecretBytes::new(password.into_bytes())),
        Err(PasswordEncoderError::ImpossiblePolicy) => Err(encoding_error("impossible-policy")),
        Err(PasswordEncoderError::EmptyPool) => Err(encoding_error("empty-pool")),
        Err(PasswordEncoderError::ExhaustedAttempts) => Err(encoding_error("exhausted-attempts")),
        Err(PasswordEncoderError::InvalidPolicyJson(_)) => Err(encoding_error("invalid-policy")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::erasure_probe;

    fn default_policy() -> NormalizedPasswordPolicy {
        NormalizedPasswordPolicy {
            min_len: 20,
            max_len: 32,
            require_upper: true,
            require_lower: true,
            require_digit: true,
            require_symbol: true,
            allowed_symbols: "!@#$%^&*".to_owned(),
            forbid_whitespace: true,
            forbidden_substrings: vec![],
        }
    }

    #[test]
    fn matches_canonical_encoder_and_erases_secret() {
        erasure_probe::take();
        let out = encode_password(
            SecretBytes::new(b"raw-upspa-secret-for-tests".to_vec()),
            default_policy(),
            "alice@example.com".to_owned(),
            0,
        )
        .expect("encodes");
        // Corpus vector v001, produced by the canonical encoder.
        assert_eq!(out.bytes, b"HlVIsTPtj9Tb4scuQGHqxxz^22yQHAfy");
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn rejects_non_normalized_policy_and_erases_secret() {
        erasure_probe::take();
        let mut policy = default_policy();
        policy.min_len = 4; // normalizes to 8
        assert_eq!(
            encode_password(
                SecretBytes::new(b"secret".to_vec()),
                policy,
                "a".to_owned(),
                0
            ),
            Err(MobileError::PasswordEncoding {
                reason_code: "policy-not-normalized".to_owned()
            })
        );
        assert_eq!(erasure_probe::take(), vec![true]);
    }

    #[test]
    fn rejects_non_utf8_secret_and_erases_it() {
        erasure_probe::take();
        assert_eq!(
            encode_password(
                SecretBytes::new(vec![0xff, 0xfe]),
                default_policy(),
                "a".to_owned(),
                0
            ),
            Err(MobileError::PasswordEncoding {
                reason_code: "secret-not-utf8".to_owned()
            })
        );
        assert_eq!(erasure_probe::take(), vec![true]);
    }
}
