//! Deterministic property and boundary tests for the UpSPA password encoder.
//!
//! Validates output length boundaries, permitted and required character classes,
//! determinism and repeatability, and API error states for invalid policies.

use std::collections::HashSet;
use upspa_core::password_encoder::{
    encode_secret_as_password, encode_secret_as_password_json, PasswordEncoderError, PasswordPolicy,
};

const TEST_SECRET: &str = "test-secret-upspa-keying-material";
const TEST_ACCOUNT: &str = "user@example.com";

fn make_base_policy() -> PasswordPolicy {
    PasswordPolicy {
        min_len: 20,
        max_len: 32,
        require_upper: true,
        require_lower: true,
        require_digit: true,
        require_symbol: true,
        allowed_symbols: "!@#$%^&*".to_string(),
        forbid_whitespace: true,
        forbidden_substrings: vec![],
    }
}

// ============================================================================
// Output Length Properties
// ============================================================================

#[test]
fn output_length_within_configured_bounds() {
    let lengths = [
        (8, 8),
        (12, 16),
        (16, 16),
        (20, 32),
        (32, 32),
        (30, 45),
        (64, 64),
    ];

    for (min_len, max_len) in lengths {
        let mut policy = make_base_policy();
        policy.min_len = min_len;
        policy.max_len = max_len;

        let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 0)
            .expect("valid policy within bounds should succeed");

        let len = pw.chars().count() as u32;
        assert!(
            len >= min_len && len <= max_len,
            "expected len in [{}, {}], got {}",
            min_len,
            max_len,
            len
        );
    }
}

#[test]
fn output_length_exact_matching_formula() {
    // The encoder computes length as:
    // length = max_len.min(min_len.max(max_len.min(32)).max(required.len()))
    let test_cases = [
        // (min_len, max_len, required_count, expected_len)
        (8, 8, 4, 8),
        (10, 20, 4, 20), // min_len.max(20) = 20, max_len = 20
        (16, 16, 4, 16),
        (20, 32, 4, 32), // min(32, 32) = 32, max(20, 32) = 32
        (35, 50, 4, 35), // max_len.min(32) = 32, max(35, 32) = 35
        (64, 64, 4, 64),
    ];

    for (min_len, max_len, _req_cnt, expected_len) in test_cases {
        let mut policy = make_base_policy();
        policy.min_len = min_len;
        policy.max_len = max_len;

        let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 0)
            .expect("should produce password");

        assert_eq!(
            pw.chars().count() as u32,
            expected_len,
            "mismatch for bounds ({min_len}, {max_len})"
        );
    }
}

#[test]
fn output_length_clamping_for_underflow_overflow_and_inverted() {
    // Underflow: min_len < 8, max_len < 8 -> clamped to min 8, max 8
    let mut underflow = make_base_policy();
    underflow.min_len = 2;
    underflow.max_len = 5;
    let pw_underflow = encode_secret_as_password(TEST_SECRET, &underflow, TEST_ACCOUNT, 0)
        .expect("underflow policy should be normalized to min_len=8");
    assert_eq!(pw_underflow.chars().count(), 8);

    // Overflow: max_len > 64 -> clamped to 64
    let mut overflow = make_base_policy();
    overflow.min_len = 20;
    overflow.max_len = 128;
    let pw_overflow = encode_secret_as_password(TEST_SECRET, &overflow, TEST_ACCOUNT, 0)
        .expect("overflow policy should be normalized to max_len=64");
    assert!(pw_overflow.chars().count() <= 64);

    // Inverted bounds: min_len > max_len (e.g. min 25, max 10)
    // normalize_policy: requested_max = 10.min(64) = 10, min_len = 25.max(8) = 25,
    // max_len = 10.max(25) = 25. Thus both become 25.
    let mut inverted = make_base_policy();
    inverted.min_len = 25;
    inverted.max_len = 10;
    let pw_inverted = encode_secret_as_password(TEST_SECRET, &inverted, TEST_ACCOUNT, 0)
        .expect("inverted policy should be clamped cleanly");
    assert_eq!(pw_inverted.chars().count(), 25);
}

#[test]
fn minimum_above_64_is_not_rejected_or_capped() {
    // Compatibility limitation: capping requested max does not cap min.
    let mut policy = make_base_policy();
    policy.min_len = 65;
    policy.max_len = 65;
    let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 0)
        .expect("existing API accepts min_len above 64");
    assert_eq!(pw.chars().count(), 65);
}

// ============================================================================
// Character Class Properties
// ============================================================================

#[test]
fn required_character_classes_always_present() {
    let policy = make_base_policy();
    for counter in 0..10 {
        let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, counter)
            .expect("encoding should succeed");

        assert!(
            pw.chars().any(|c| c.is_ascii_lowercase()),
            "missing lowercase"
        );
        assert!(
            pw.chars().any(|c| c.is_ascii_uppercase()),
            "missing uppercase"
        );
        assert!(pw.chars().any(|c| c.is_ascii_digit()), "missing digit");
        assert!(
            pw.chars().any(|c| "!@#$%^&*".contains(c)),
            "missing required symbol"
        );
    }
}

#[test]
fn permitted_character_classes_strictly_honored() {
    // 1. Lowercase + digits only (no uppercase, no symbols)
    let lower_digit_policy = PasswordPolicy {
        min_len: 16,
        max_len: 20,
        require_upper: false,
        require_lower: true,
        require_digit: true,
        require_symbol: false,
        allowed_symbols: String::new(),
        forbid_whitespace: true,
        forbidden_substrings: vec![],
    };
    let pw1 = encode_secret_as_password(TEST_SECRET, &lower_digit_policy, TEST_ACCOUNT, 0)
        .expect("encoding lower+digit should succeed");
    for c in pw1.chars() {
        assert!(
            c.is_ascii_lowercase() || c.is_ascii_digit(),
            "unexpected char {c} in lower+digit policy"
        );
    }

    // 2. Letters only (uppercase + lowercase, no digits, no symbols)
    let letters_policy = PasswordPolicy {
        min_len: 16,
        max_len: 20,
        require_upper: true,
        require_lower: true,
        require_digit: false,
        require_symbol: false,
        allowed_symbols: String::new(),
        forbid_whitespace: true,
        forbidden_substrings: vec![],
    };
    let pw2 = encode_secret_as_password(TEST_SECRET, &letters_policy, TEST_ACCOUNT, 0)
        .expect("encoding letters-only should succeed");
    for c in pw2.chars() {
        assert!(
            c.is_ascii_alphabetic(),
            "unexpected non-alpha char {c} in letters policy"
        );
    }

    // 3. Digits only
    let digits_policy = PasswordPolicy {
        min_len: 10,
        max_len: 12,
        require_upper: false,
        require_lower: false,
        require_digit: true,
        require_symbol: false,
        allowed_symbols: String::new(),
        forbid_whitespace: true,
        forbidden_substrings: vec![],
    };
    let pw3 = encode_secret_as_password(TEST_SECRET, &digits_policy, TEST_ACCOUNT, 0)
        .expect("encoding digits-only should succeed");
    for c in pw3.chars() {
        assert!(c.is_ascii_digit(), "unexpected non-digit char {c}");
    }
}

#[test]
fn restricted_allowed_symbols_set_strictly_isolated() {
    let mut policy = make_base_policy();
    policy.allowed_symbols = "-_.".to_string();

    for counter in 0..5 {
        let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, counter)
            .expect("restricted symbols encoding should succeed");

        for c in pw.chars() {
            if !c.is_ascii_alphanumeric() {
                assert!(
                    "-_.".contains(c),
                    "char {c} is not among allowed symbols '-_.'"
                );
            }
        }
    }
}

#[test]
fn whitespace_is_strictly_forbidden_when_configured() {
    let mut policy = make_base_policy();
    policy.forbid_whitespace = true;
    policy.allowed_symbols = " ! @ # \t \n ".to_string();

    let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 0)
        .expect("policy with whitespace in symbols should normalize and succeed");

    assert!(
        !pw.chars().any(|c| c.is_whitespace()),
        "whitespace found in output"
    );
}

#[test]
fn forbidden_substrings_and_account_id_are_excluded() {
    let mut policy = make_base_policy();
    policy.forbidden_substrings = vec!["admin".to_string(), "pass".to_string(), "123".to_string()];
    let account = "  Alice.Smith  ";

    for counter in 0..5 {
        let pw = encode_secret_as_password(TEST_SECRET, &policy, account, counter)
            .expect("should satisfy forbidden substrings");

        let lower = pw.to_lowercase();
        assert!(!lower.contains("admin"), "contains forbidden 'admin'");
        assert!(!lower.contains("pass"), "contains forbidden 'pass'");
        assert!(!lower.contains("123"), "contains forbidden '123'");
        assert!(
            !lower.contains("alice.smith"),
            "contains trimmed lower account id"
        );
    }
}

// ============================================================================
// Repeatability and Determinism
// ============================================================================

#[test]
fn repeatability_same_inputs_produce_identical_output() {
    let policy = make_base_policy();
    let a = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 42).unwrap();
    let b = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 42).unwrap();
    let c = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 42).unwrap();
    assert_eq!(a, b);
    assert_eq!(b, c);
}

#[test]
fn counter_rotation_produces_unique_passwords() {
    let policy = make_base_policy();
    let mut seen = HashSet::new();

    for counter in 0..20 {
        let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, counter).unwrap();
        assert!(
            seen.insert(pw),
            "collision detected on counter rotation at ctr={counter}"
        );
    }
}

#[test]
fn secret_and_account_id_differentiation() {
    let policy = make_base_policy();

    let pw_secret1 = encode_secret_as_password("secret-alpha", &policy, TEST_ACCOUNT, 0).unwrap();
    let pw_secret2 = encode_secret_as_password("secret-beta", &policy, TEST_ACCOUNT, 0).unwrap();
    assert_ne!(
        pw_secret1, pw_secret2,
        "different secrets must produce different passwords"
    );

    let pw_acc1 = encode_secret_as_password(TEST_SECRET, &policy, "alice", 0).unwrap();
    let pw_acc2 = encode_secret_as_password(TEST_SECRET, &policy, "bob", 0).unwrap();
    assert_ne!(
        pw_acc1, pw_acc2,
        "different accounts must produce different passwords"
    );

    // Case and whitespace invariance on account id
    let pw_acc_upper = encode_secret_as_password(TEST_SECRET, &policy, "  ALICE  ", 0).unwrap();
    assert_eq!(
        pw_acc1, pw_acc_upper,
        "trimmed lowercase account IDs must produce identical passwords"
    );
}

#[test]
fn json_policy_matches_struct_policy_identically() {
    let policy = make_base_policy();
    let json = serde_json::to_string(&policy).expect("serialize policy");

    let from_struct = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 7).unwrap();
    let from_json = encode_secret_as_password_json(TEST_SECRET, &json, TEST_ACCOUNT, 7).unwrap();

    assert_eq!(
        from_struct, from_json,
        "JSON string representation must match struct encoding identically"
    );
}

// ============================================================================
// Boundary and Invalid Policy Error Properties
// ============================================================================

#[test]
fn empty_character_pool_returns_empty_pool_error() {
    let empty_pool_policy = PasswordPolicy {
        min_len: 12,
        max_len: 20,
        require_upper: false,
        require_lower: false,
        require_digit: false,
        require_symbol: false,
        allowed_symbols: String::new(),
        forbid_whitespace: true,
        forbidden_substrings: vec![],
    };

    let result = encode_secret_as_password(TEST_SECRET, &empty_pool_policy, TEST_ACCOUNT, 0);
    match result {
        Err(PasswordEncoderError::EmptyPool) => {}
        other => panic!("expected EmptyPool error, got: {:?}", other),
    }
}

#[test]
fn impossible_policy_exhausts_attempts() {
    // Digits only policy where all single digits 0-9 are in forbidden_substrings
    let impossible_policy = PasswordPolicy {
        min_len: 8,
        max_len: 12,
        require_upper: false,
        require_lower: false,
        require_digit: true,
        require_symbol: false,
        allowed_symbols: String::new(),
        forbid_whitespace: true,
        forbidden_substrings: (0..=9).map(|d| d.to_string()).collect(),
    };

    let result = encode_secret_as_password(TEST_SECRET, &impossible_policy, TEST_ACCOUNT, 0);
    match result {
        Err(PasswordEncoderError::ExhaustedAttempts) => {}
        other => panic!("expected ExhaustedAttempts error, got: {:?}", other),
    }
}

#[test]
fn invalid_json_returns_invalid_policy_json_error() {
    let result = encode_secret_as_password_json(TEST_SECRET, "{invalid json", TEST_ACCOUNT, 0);
    match result {
        Err(PasswordEncoderError::InvalidPolicyJson(_)) => {}
        other => panic!("expected InvalidPolicyJson error, got: {:?}", other),
    }
}

#[test]
fn json_length_fields_reject_values_outside_u32_and_missing_fields() {
    let base = serde_json::to_value(make_base_policy()).expect("serialize synthetic policy");
    for field in ["minLen", "maxLen"] {
        for value in [
            serde_json::json!(-1),
            serde_json::json!(8.5),
            serde_json::json!(4294967296u64),
        ] {
            let mut policy = base.clone();
            policy[field] = value;
            let result =
                encode_secret_as_password_json(TEST_SECRET, &policy.to_string(), TEST_ACCOUNT, 0);
            assert!(
                matches!(result, Err(PasswordEncoderError::InvalidPolicyJson(_))),
                "out-of-range JSON length must fail before encoding"
            );
        }
        let mut policy = base.clone();
        policy.as_object_mut().unwrap().remove(field);
        assert!(matches!(
            encode_secret_as_password_json(TEST_SECRET, &policy.to_string(), TEST_ACCOUNT, 0),
            Err(PasswordEncoderError::InvalidPolicyJson(_))
        ));
    }
}

#[test]
fn default_symbol_fallback_when_require_symbol_is_true_and_allowed_symbols_empty() {
    let mut policy = make_base_policy();
    policy.require_symbol = true;
    policy.allowed_symbols = String::new();

    let pw = encode_secret_as_password(TEST_SECRET, &policy, TEST_ACCOUNT, 0)
        .expect("should normalize to default symbols and succeed");

    assert!(
        pw.chars().any(|c| "!@#$%^&*".contains(c)),
        "default symbols '!@#$%^&*' should be present"
    );
}

#[test]
fn empty_account_id_produces_valid_password() {
    let policy = make_base_policy();
    let pw = encode_secret_as_password(TEST_SECRET, &policy, "", 0)
        .expect("empty account_id should be accepted");
    assert!(!pw.is_empty());
}
