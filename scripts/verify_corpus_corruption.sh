#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Verifying Corpus Corruption Detection ==="
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

TMP_CORPUS="$TMP_DIR/vectors.json"
python3 -c '
import json
with open("test-vectors/compatibility-profile-v1/vectors.json") as f:
    data = json.load(f)
for v in data:
    if v["id"] == "v001":
        v["expected"] = "corrupted_expected_v001"
with open("'"$TMP_CORPUS"'", "w") as f:
    json.dump(data, f, indent=2)
'

echo "1. Proving Rust corpus consumer detects corrupted vector..."
set +e
RUST_OUT=$(UPSPA_TEST_VECTORS_PATH="$TMP_CORPUS" cargo test --locked -p upspa-core --test vectors_password_encoder 2>&1)
RUST_STATUS=$?
set -e

if [ $RUST_STATUS -eq 0 ]; then
  echo "FAIL: Rust consumer unexpectedly succeeded on corrupted vector" >&2
  exit 1
fi

if ! echo "$RUST_OUT" | grep -q "password mismatch for vector IDs: v001"; then
  echo "FAIL: Rust consumer output did not contain expected safe mismatch notification for v001" >&2
  echo "$RUST_OUT" >&2
  exit 1
fi

# Verify no derived secrets or candidate passwords leaked in failure output
if echo "$RUST_OUT" | grep -q "corrupted_expected_v001"; then
  echo "FAIL: Rust consumer leaked the corrupted expected string in failure output" >&2
  exit 1
fi
echo "✓ Rust consumer safely detected corruption (v001 mismatch reported without leaking secret material)."

echo "2. Proving TypeScript corpus consumer detects corrupted vector..."
set +e
TS_OUT=$(UPSPA_TEST_VECTORS_PATH="$TMP_CORPUS" npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts 2>&1)
TS_STATUS=$?
set -e

if [ $TS_STATUS -eq 0 ]; then
  echo "FAIL: TypeScript consumer unexpectedly succeeded on corrupted vector" >&2
  exit 1
fi

if ! echo "$TS_OUT" | grep -q "v001: password mismatch"; then
  echo "FAIL: TypeScript consumer output did not contain expected safe mismatch notification for v001" >&2
  echo "$TS_OUT" >&2
  exit 1
fi

if echo "$TS_OUT" | grep -q "corrupted_expected_v001"; then
  echo "FAIL: TypeScript consumer leaked the corrupted expected string in failure output" >&2
  exit 1
fi
echo "✓ TypeScript consumer safely detected corruption (v001 mismatch reported without leaking secret material)."

echo "=== Corruption detection verified successfully for both consumers ==="
