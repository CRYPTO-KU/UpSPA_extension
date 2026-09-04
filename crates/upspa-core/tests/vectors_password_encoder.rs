//! Compatibility test suite for password encoding vectors.
//!
//! The Rust parity contract currently expects fully normalized policies.
//! Rust and TypeScript do not normalize every raw policy identically, so
//! partial-policy support remains future integration work.

use serde::Deserialize;
use std::fs;
use upspa_core::password_encoder::{encode_secret_as_password, PasswordPolicy};

/// Test vector loaded from the compatibility profile corpus.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CorpusVector {
    id: String,
    secret_b64: String,
    policy: PasswordPolicy,
    account_id: String,
    counter: u32,
    expected: String,
    reject: bool,
}

/// Loads the test vectors from the JSON corpus file.
fn load_corpus() -> Vec<CorpusVector> {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../test-vectors/compatibility-profile-v1/vectors.json");
    let raw = fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("Could not read corpus at {}: {e}", path.display()));
    serde_json::from_str(&raw).expect("vectors.json must be valid JSON")
}

#[test]
fn corpus_has_at_least_20_accepted_vectors() {
    let vectors = load_corpus();
    let accepted = vectors.iter().filter(|v| !v.reject).count();
    assert!(
        accepted >= 20,
        "need at least 20 accepted vectors, found {accepted}"
    );
}

#[test]
fn corpus_has_exactly_one_reject_vector() {
    let vectors = load_corpus();
    let rejected = vectors.iter().filter(|v| v.reject).count();
    assert_eq!(
        rejected, 1,
        "expected exactly 1 reject vector, found {rejected}"
    );
}

#[test]
fn all_accepted_vectors_match_expected() {
    let vectors = load_corpus();
    let mut failures = 0usize;

    for v in vectors.iter().filter(|v| !v.reject) {
        match encode_secret_as_password(&v.secret_b64, &v.policy, &v.account_id, v.counter) {
            Ok(pw) if pw == v.expected => eprintln!("PASS [{}]", v.id),
            Ok(_) => {
                eprintln!("FAIL [{}]: password mismatch", v.id);
                failures += 1;
            }
            Err(_) => {
                eprintln!("FAIL [{}]: password mismatch", v.id);
                failures += 1;
            }
        }
    }

    assert_eq!(
        failures, 0,
        "{failures} vector(s) failed — see stderr above"
    );
}

#[test]
fn reject_vector_does_not_match_mutated_expected() {
    for v in load_corpus().iter().filter(|v| v.reject) {
        let pw = encode_secret_as_password(&v.secret_b64, &v.policy, &v.account_id, v.counter)
            .unwrap_or_else(|_| panic!("FAIL [{}]: password mismatch", v.id));
        if pw == v.expected {
            panic!("FAIL [{}]: password mismatch", v.id);
        }
    }
}
