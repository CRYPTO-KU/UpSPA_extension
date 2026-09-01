use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;
use zeroize::Zeroizing;

#[derive(Debug, Error)]
pub enum PasswordEncoderError {
    #[error("Password policy is impossible: more required classes than maximum length.")]
    ImpossiblePolicy,
    #[error("Password policy is impossible: no allowed character set.")]
    EmptyPool,
    #[error("Could not encode a password that satisfies the site policy. Adjust the policy and try again.")]
    ExhaustedAttempts,
    #[error("Password policy JSON is invalid: {0}")]
    InvalidPolicyJson(#[from] serde_json::Error),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PasswordPolicy {
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

const LOWER: &str = "abcdefghijklmnopqrstuvwxyz";
const UPPER: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGIT: &str = "0123456789";
const DEFAULT_SYMBOLS: &str = "!@#$%^&*";
const MAX_ATTEMPTS: u32 = 128;

fn unique_chars(s: &str) -> String {
    let mut seen = std::collections::BTreeSet::new();
    s.chars().filter(|c| seen.insert(*c)).collect()
}

fn normalize_policy(policy: &PasswordPolicy) -> PasswordPolicy {
    let requested_max = policy.max_len.min(64);
    let min_len = policy.min_len.max(8);
    let max_len = requested_max.max(min_len);

    let mut allowed_symbols = policy.allowed_symbols.clone();
    if policy.forbid_whitespace {
        allowed_symbols = allowed_symbols.chars().filter(|c| !c.is_whitespace()).collect();
    }
    allowed_symbols = unique_chars(&allowed_symbols);
    if policy.require_symbol && allowed_symbols.is_empty() {
        allowed_symbols = DEFAULT_SYMBOLS.to_string();
    }

    let forbidden_substrings = policy
        .forbidden_substrings
        .iter()
        .map(|s| s.trim().to_lowercase())
        .filter(|s| !s.is_empty())
        .collect();

    PasswordPolicy {
        min_len,
        max_len,
        require_upper: policy.require_upper,
        require_lower: policy.require_lower,
        require_digit: policy.require_digit,
        require_symbol: policy.require_symbol,
        allowed_symbols,
        forbid_whitespace: policy.forbid_whitespace,
        forbidden_substrings,
    }
}

fn required_charsets(policy: &PasswordPolicy) -> Vec<&str> {
    let mut sets: Vec<&str> = Vec::new();
    if policy.require_lower { sets.push(LOWER); }
    if policy.require_upper { sets.push(UPPER); }
    if policy.require_digit { sets.push(DIGIT); }
    if policy.require_symbol { sets.push(policy.allowed_symbols.as_str()); }
    sets
}

fn build_pool(policy: &PasswordPolicy) -> String {
    let mut raw = String::new();
    if policy.require_lower { raw.push_str(LOWER); }
    if policy.require_upper { raw.push_str(UPPER); }
    if policy.require_digit { raw.push_str(DIGIT); }
    if policy.require_symbol { raw.push_str(&policy.allowed_symbols); }
    unique_chars(&raw)
}

fn sha256_bytes(input: &str) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(input.as_bytes());
    h.finalize().into()
}

fn expand_bytes(seed: &str, length: usize) -> Zeroizing<Vec<u8>> {
    let mut chunks = Zeroizing::new(Vec::with_capacity(length + 32));
    let mut block = 0u32;
    while chunks.len() < length {
        chunks.extend_from_slice(&sha256_bytes(&format!("{}|block={}", seed, block)));
        block += 1;
    }
    chunks.truncate(length);
    chunks
}

fn pick_char(charset: &str, byte: u8) -> char {
    let chars: Vec<char> = charset.chars().collect();
    chars[byte as usize % chars.len()]
}

fn build_candidate(chars: &[char], shuffle_bytes: &[u8]) -> String {
    let mut out = chars.to_vec();
    for i in (1..out.len()).rev() {
        out.swap(i, (shuffle_bytes[i] as usize) % (i + 1));
    }
    out.iter().collect()
}

fn password_satisfies_policy(password: &str, policy: &PasswordPolicy, account_id: &str) -> bool {
    let len = password.chars().count() as u32;
    if len < policy.min_len || len > policy.max_len { return false; }
    if policy.require_upper && !password.chars().any(|c| c.is_ascii_uppercase()) { return false; }
    if policy.require_lower && !password.chars().any(|c| c.is_ascii_lowercase()) { return false; }
    if policy.require_digit && !password.chars().any(|c| c.is_ascii_digit()) { return false; }
    if policy.require_symbol {
        let sym_chars: Vec<char> = policy.allowed_symbols.chars().collect();
        if !password.chars().any(|c| sym_chars.contains(&c)) { return false; }
    }
    if policy.forbid_whitespace && password.chars().any(|c| c.is_whitespace()) { return false; }
    let lower_password = password.to_lowercase();
    for forbidden in &policy.forbidden_substrings {
        if !forbidden.is_empty() && lower_password.contains(forbidden.as_str()) { return false; }
    }
    let clean_account_id = account_id.trim().to_lowercase();
    if !clean_account_id.is_empty() && lower_password.contains(&clean_account_id) { return false; }
    true
}

fn canonical_policy(policy: &PasswordPolicy) -> String {
    // JSON.stringify preserves insertion order; serde_json::json!{} sorts keys.
    // This format! mirrors the exact field order of the TypeScript object literal.
    let p = normalize_policy(policy);
    let forbidden_json = format!(
        "[{}]",
        p.forbidden_substrings
            .iter()
            .map(|s| serde_json::to_string(s).unwrap())
            .collect::<Vec<_>>()
            .join(",")
    );
    format!(
        r#"{{"minLen":{minLen},"maxLen":{maxLen},"requireUpper":{requireUpper},"requireLower":{requireLower},"requireDigit":{requireDigit},"requireSymbol":{requireSymbol},"allowedSymbols":{allowedSymbols},"forbidWhitespace":{forbidWhitespace},"forbiddenSubstrings":{forbiddenSubstrings}}}"#,
        minLen = p.min_len,
        maxLen = p.max_len,
        requireUpper = p.require_upper,
        requireLower = p.require_lower,
        requireDigit = p.require_digit,
        requireSymbol = p.require_symbol,
        allowedSymbols = serde_json::to_string(&p.allowed_symbols).unwrap(),
        forbidWhitespace = p.forbid_whitespace,
        forbiddenSubstrings = forbidden_json,
    )
}

pub fn encode_secret_as_password(
    secret_b64: &str,
    policy: &PasswordPolicy,
    account_id: &str,
    counter: u32,
) -> Result<String, PasswordEncoderError> {
    let policy = normalize_policy(policy);
    let required = required_charsets(&policy);

    if required.len() as u32 > policy.max_len {
        return Err(PasswordEncoderError::ImpossiblePolicy);
    }

    let pool = build_pool(&policy);
    if pool.is_empty() {
        return Err(PasswordEncoderError::EmptyPool);
    }

    let length = policy
        .max_len
        .min(policy.min_len.max(policy.max_len.min(32)).max(required.len() as u32))
        as usize;

    let account_key = account_id.trim().to_lowercase();
    let canon = canonical_policy(&policy);

    for attempt in 0..MAX_ATTEMPTS {
        let seed = format!(
            "upspa-password-encoding-v2|{}|{}|{}|ctr={}|try={}",
            secret_b64, canon, account_key, counter, attempt
        );
        let needed = length * 2 + required.len();
        let bytes = expand_bytes(&seed, needed);

        let mut chars: Vec<char> = required
            .iter()
            .enumerate()
            .map(|(i, cs)| pick_char(cs, bytes[i]))
            .collect();
        for i in required.len()..length {
            chars.push(pick_char(&pool, bytes[i]));
        }

        let password = build_candidate(&chars, &bytes[length..length * 2]);
        if password_satisfies_policy(&password, &policy, account_id) {
            return Ok(password);
        }
    }

    Err(PasswordEncoderError::ExhaustedAttempts)
}

pub fn encode_secret_as_password_json(
    secret_b64: &str,
    policy_json: &str,
    account_id: &str,
    counter: u32,
) -> Result<String, PasswordEncoderError> {
    let policy: PasswordPolicy = serde_json::from_str(policy_json)?;
    encode_secret_as_password(secret_b64, &policy, account_id, counter)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn default_policy() -> PasswordPolicy {
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

    #[test]
    fn satisfies_default_policy() {
        let policy = default_policy();
        let pw = encode_secret_as_password("raw-upspa-secret-for-tests", &policy, "alice@example.com", 0).unwrap();
        assert!(password_satisfies_policy(&pw, &normalize_policy(&policy), "alice@example.com"));
    }

    #[test]
    fn deterministic_same_counter() {
        let policy = default_policy();
        let a = encode_secret_as_password("raw-upspa-secret-for-tests", &policy, "alice@example.com", 3).unwrap();
        let b = encode_secret_as_password("raw-upspa-secret-for-tests", &policy, "alice@example.com", 3).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn different_counter_different_password() {
        let policy = default_policy();
        let a = encode_secret_as_password("raw-upspa-secret-for-tests", &policy, "alice@example.com", 1).unwrap();
        let b = encode_secret_as_password("raw-upspa-secret-for-tests", &policy, "alice@example.com", 2).unwrap();
        assert_ne!(a, b);
    }
}
