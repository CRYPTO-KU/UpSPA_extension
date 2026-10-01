# Encoder Conformance Specification

## Profile baseline and qualification environment

Profile v1 uses `upspa-password-encoding-v2` and the unchanged 22-vector corpus:
21 accepted vectors and one intentionally mismatched comparison control (`v022`).
The [manifest](../test-vectors/compatibility-profile-v1/manifest.json) records
`fd00014` as the source of the browser encoder used for generation. The current
`passwordPolicy.ts` matches that source; regeneration must preserve the corpus,
classifications and manifest. A mismatched expected value is not an invalid-policy case.

The [dedicated workflow](../.github/workflows/encoder-conformance.yml) selects Rust
1.95.0, Node.js 24.19.0 and Python 3.12. Its job-level `RUSTUP_TOOLCHAIN=1.95.0`
overrides the repository's `stable` selection for all Cargo calls, including nested
helpers. It verifies the effective Rust and Node versions before testing. Root
pins, lockfiles, UniFFI versions, corpus schema and generated bindings are unchanged.

The previous Rust 1.81.0 qualification claim was incorrect: installing it did not
override the root toolchain, and explicit 1.81 cannot consume the locked Edition
2024 dependency (`base64ct` 1.8.3). Installing a toolchain is not qualification.
The workflow summary reports actual step outcomes, including failure, skipped and
cancelled checks. A green local run on a different Rust version is not proof that
the pinned CI environment passed. See [compatibility gaps](encoder-compatibility-gaps.md)
for remaining qualification boundaries.

## Inputs and supported policy boundary

The parity contract uses a complete, normalized policy, ASCII character pools and
account identifiers, integral lengths `8 <= minLen <= maxLen <= 64`, and a counter
in `0..=4294967295` (Rust `u32`). Secret material is passed as a string without
base64 decoding. Raw/partial policies and Unicode equivalence are not qualified
by the committed corpus; Rust requires every policy field.

Both implementations perform these length operations on complete positive integer
inputs: `minLen = max(8, requestedMin)` and
`maxLen = max(minLen, min(64, requestedMax))`. Thus **64 is not an enforced output
cap**: `minLen=65, maxLen=65` normalizes to `(65,65)` and emits 65 characters in both
implementations. Dedicated synthetic tests retain this observed limitation without
changing the API or adding a new rejection rule. Large minima can cause excessive
allocation/work; do not treat arbitrary representable lengths as supported policies.

Other normalization behavior:

- Remove whitespace from allowed symbols when whitespace is forbidden; deduplicate symbols in order.
- Fall back to `!@#$%^&*` when symbols are required and the cleaned list is empty.
- Trim and lowercase forbidden substrings, dropping empty entries.
- Trim and lowercase the account identifier for the seed and exclusion check.
- Serialize the normalized policy as compact JSON in this field order:
  `minLen`, `maxLen`, `requireUpper`, `requireLower`, `requireDigit`,
  `requireSymbol`, `allowedSymbols`, `forbidWhitespace`, `forbiddenSubstrings`.

TypeScript also defaults/coerces raw inputs; these operations are not universally
identical to Rust. No new shared normalization rule is implied by this audit.

## Candidate generation

For each attempt `0..127`, the seed is
`upspa-password-encoding-v2|secret|canonicalPolicy|accountKey|ctr=counter|try=attempt`.
SHA-256 hashes of `seed|block=N`, starting at block zero, are concatenated and
truncated to `2 * length + requiredClassCount` bytes. This is block-counter
expansion, not HKDF.

Length is deterministic:
`min(maxLen, max(minLen, min(32, maxLen), requiredClassCount))`.
Required classes are ordered lowercase, uppercase, digits, symbols; only enabled
classes contribute to the character pool. Bytes select characters by modulo pool
size. The first required characters guarantee class presence, subsequent bytes fill
the pool, and the second length-sized byte region drives the descending shuffle.
The encoder returns the first policy-valid candidate or fails after 128 attempts.
An empty pool fails before generation. The more-required-classes-than-max guard
is unreachable with the present four classes and normalized minimum of eight.

## Counter semantics

The caller chooses a stable rotation counter. Attempts resample within that counter;
TypeScript returns the requested counter. Identical inputs reproduce the same
password for registration and login. Tests check distinct outputs for synthetic
rotation cases; they do not establish a mathematical no-collision guarantee.

## Reproduction and regression controls

With real rustup and the selected toolchain installed, install Node dependencies
from the root lockfile and run:

```bash
rustup toolchain install 1.95.0 --component rustfmt --component clippy
export RUSTUP_TOOLCHAIN=1.95.0
rustc --version
cargo --version
node --version # expected v24.19.0 for the CI environment
npm ci
./scripts/run_encoder_conformance.sh
```

The runner uses the current environment and prints versions; it does not install
or silently qualify a different toolchain. Individual checks:

```bash
cargo test --locked -p upspa-core --test vectors_password_encoder
cargo test --locked -p upspa-core --test encoder_properties
npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts
./node_modules/.bin/tsx scripts/gen_password_vectors.mjs
profile_changes=$(git status --porcelain --untracked-files=all -- test-vectors/compatibility-profile-v1/) && test -z "$profile_changes"
./scripts/verify_corpus_corruption.sh
python3 scripts/test_encoder_conformance.py
python3 tools/security-gates/run_gates.py .
python3 tools/security-gates/test_negative_fixtures.py
python3 tools/security-gates/test_positive_fixtures.py
cargo fmt --all -- --check
```

Regenerate against a clean committed corpus or in a disposable checkout. The status
check covers the entire profile directory, including staged and unstaged corpus/manifest
edits and unexpected untracked files, without printing credential-bearing diffs.

The corruption helper modifies only an accepted synthetic vector in a temporary
file. Both consumers must fail with a vector-ID mismatch. Before classifying any
result it scans private captured output for all original expected values (including
complete accepted candidates), synthetic secret inputs and the replacement string.
It never prints captured output, even on unexpected success or missing markers.
This is a complete-value regression check, not proof against partial, encoded or
otherwise transformed leaks.

The stdlib regression suite simulates an earlier failed step plus skipped/cancelled
steps, checks dependency-only trigger paths for push and PR, and verifies the
workflow-local pins. Corpus, manifest and unexpected-file mutations must fail the
regeneration check, with a clean profile retained as the control. Synthetic command fixtures log protected values from either
consumer; every logging fault must make the helper fail without echoing the value.
A safe mismatch remains a passing control. Actual consumer logging and Rust length
faults are exercised only in disposable copies and removed before handoff.

## Verification baseline

Work began on clean `mobile-dev` at
`0d2784064eef62309e57d9b866620c840409d2ab`. Acceptance criteria were effective
workflow-local pins, truthful summaries, dependency-input triggers, observed
length-boundary tests, and leakage rejection without dumping captured output.

The final verification below supersedes the initial Rust 1.98 local run and its
formatting limitation. No commit, push, merge or remote workflow run was performed.

## Qualification results and warning audit

Official Rust 1.95.0 components were downloaded to `/tmp`, checked against the
release manifest's SHA-256 values and installed there. Neither the system Rust
installation nor repository pins changed. Official Rust 1.81 compiler/Cargo tools
were installed in a second temporary prefix for failure reproduction.

- `PATH=/tmp/upspa-rust-1.95.0/bin:$PATH RUSTUP_TOOLCHAIN=1.95.0 CARGO_TARGET_DIR=/tmp/upspa-target-1.95.0 cargo test --locked -p upspa-core`:
  passed all 31 tests, including 19 encoder properties and 4 corpus tests, with no
  compiler warnings. Added JSON length-field rejection controls for negative,
  fractional, out-of-u32-range and missing values; no normalization rule changed.
- `npm ci --cache /tmp/upspa-encoder-npm-cache`: succeeded in a fresh disposable
  clone with the repair applied, leaving workspace `node_modules` untouched.
  The initial sandboxed install hit DNS failures; the successful installation
  used permitted registry access. Existing npm warnings are listed separately in
  [compatibility gaps](encoder-compatibility-gaps.md).
- `PATH=/tmp/upspa-rust-1.95.0/bin:$PATH RUSTUP_TOOLCHAIN=1.95.0 CARGO_TARGET_DIR=/tmp/upspa-target-1.95.0 ./scripts/run_encoder_conformance.sh`:
  passed from `/tmp/upspa-encoder-qualified`, after locked npm installation:
  4 Rust corpus tests, 19 Rust properties, 18 TypeScript tests, unchanged profile,
  safe corruption detection, 7 qualification controls, all security gates,
  8 negative fixtures, 1 positive fixture and global Rust formatting. `tsx` needed
  permission to create its local IPC socket outside the sandbox.
- `PATH=/tmp/upspa-rust-1.95.0/bin:$PATH RUSTUP_TOOLCHAIN=1.95.0 cargo fmt --all -- --check`:
  passed. Six existing core files received only import-order fixes. Formatting is
  now a required workflow/runner step and its outcome appears in the summary.
- `/tmp/upspa-actionlint/actionlint .github/workflows/encoder-conformance.yml`:
  passed with actionlint 1.7.12, whose release SHA-256 was verified before execution.
- `PATH=/tmp/upspa-rust-1.81.0/bin:$PATH cargo test --locked -p upspa-core --test vectors_password_encoder`:
  failed as expected on the locked Edition 2024 `cpufeatures` 0.3.0 manifest.
  This was the first incompatible dependency encountered here; `base64ct` 1.8.3
  also declares Edition 2024 and Rust 1.85. An isolated dependency-shaped synthetic
  manifest with Edition 2024 / Rust 1.85 was rejected by Cargo 1.81 and accepted
  by Cargo 1.95 using `cargo metadata --no-deps --format-version 1 --manifest-path`.

Controlled faults were rerun in the disposable copy with Rust 1.95 / Node 24.19:

- `cargo test --locked -p upspa-core --test encoder_properties output_length_within_configured_bounds`
  failed after appending a character to successful output. The encoder was restored.
- `bash scripts/verify_corpus_corruption.sh` incorrectly passed with the original
  helper and both actual consumers logging complete synthetic candidates. With
  the repaired helper it failed for Rust logging, then for TypeScript logging,
  and passed after restoring both consumers. Captured logs were never dumped.
- `python3 scripts/test_encoder_conformance.py` passed all 7 controls, including
  simulated failure/skipping/cancellation, both dependency trigger lists, and
  corpus/manifest/untracked-file mutations with a clean regeneration control.
- `bash -n scripts/verify_corpus_corruption.sh scripts/run_encoder_conformance.sh`,
  `git diff --check`, local documentation link checks and protected-path diff
  checks passed. The encoder source still matches recorded commit `fd00014`.

All synthetic faults remain removed. All 22
vectors, classifications, source provenance, shared manifests, lockfiles, root
pins, UniFFI versions and generated bindings remain unchanged. AI assistance was
used for the workflow, helpers, regression tests, formatting and documentation;
this is author verification, not independent human approval.

Local follow-up qualification used Rust/Cargo 1.95.0, Node 24.19.0, npm 11.17.0 and
Python 3.14.7. GitHub Actions and its Python 3.12 runtime were not executed here;
native/device qualification is outside this encoder task. Local results do not
claim a remote CI run, a merge or a complete mobile release qualification.
