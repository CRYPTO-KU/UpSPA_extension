//! Cross-language normalization controls and an explicitly excluded existing gap.
//! Browser snapshots are refreshed/checked by scripts/verify_encoder_normalization.mjs.
//! The live probe overrides the snapshots with fresh browser results in a temporary file.

use serde::Deserialize;
use sha2::{Digest, Sha256};
use upspa_core::password_encoder::{encode_secret_as_password, PasswordPolicy};

#[derive(Deserialize)]
struct Fixtures {
    cases: Vec<Case>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Case {
    id: String,
    secret_b64: String,
    account_id: String,
    counter: u32,
    policy: PasswordPolicy,
    browser_fixed_point: bool,
    expect_parity: bool,
    browser_password_sha256: String,
    browser_password: Option<String>,
}

fn load_case(id: &str) -> Case {
    let input = match std::env::var("UPSPA_NORMALIZATION_FIXTURES") {
        Ok(path) => std::fs::read_to_string(path).expect("synthetic fixture must be readable"),
        Err(_) => include_str!("fixtures/encoder_normalization.json").to_owned(),
    };
    let fixtures: Fixtures = serde_json::from_str(&input).expect("synthetic fixture must be valid");
    assert!(
        fixtures.cases.len() == 3,
        "expected three synthetic controls"
    );
    let case = fixtures
        .cases
        .into_iter()
        .find(|case| case.id == id)
        .expect("synthetic control must exist");
    if std::env::var_os("UPSPA_NORMALIZATION_FIXTURES").is_some() {
        assert!(
            case.browser_password.is_some(),
            "live fixture must contain the browser result"
        );
    }
    case
}

fn matches_browser(case: &Case) -> bool {
    let password = encode_secret_as_password(
        &case.secret_b64,
        &case.policy,
        &case.account_id,
        case.counter,
    )
    .expect("synthetic encoding must succeed");
    match &case.browser_password {
        Some(browser_password) => password == *browser_password,
        None => {
            format!("{:x}", Sha256::digest(password.as_bytes())) == case.browser_password_sha256
        }
    }
}

#[test]
fn default_policy_matches_browser() {
    let case = load_case("default-policy");
    assert!(case.browser_fixed_point && case.expect_parity);
    assert!(
        matches_browser(&case),
        "default-policy: browser/Rust parity mismatch"
    );
}

#[test]
fn shared_fixed_point_matches_browser() {
    let case = load_case("shared-fixed-point");
    assert!(case.browser_fixed_point && case.expect_parity);
    assert!(!case.policy.require_symbol && case.policy.allowed_symbols == "!@#$%^&*");
    assert!(
        matches_browser(&case),
        "shared-fixed-point: browser/Rust parity mismatch"
    );
}

#[test]
fn single_browser_pass_empty_symbols_is_excluded() {
    let case = load_case("single-pass-empty-symbols");
    assert!(!case.browser_fixed_point && !case.expect_parity);
    assert!(!case.policy.require_symbol && case.policy.allowed_symbols.is_empty());
    let matches = matches_browser(&case);
    if std::env::var("UPSPA_NORMALIZATION_REQUIRE_PARITY").as_deref() == Ok("1") {
        // The live qualification probe intentionally demonstrates this assertion failing.
        assert!(
            matches,
            "single-pass-empty-symbols: browser/Rust parity mismatch"
        );
    } else {
        // A future convergence requires review and requalification, not silent fixture updates.
        assert!(
            !matches,
            "excluded normalization gap changed; compatibility review required"
        );
    }
}
