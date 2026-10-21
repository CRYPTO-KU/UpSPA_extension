#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Captured consumer output stays private until checked, and is never dumped.
umask 077
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
TMP_CORPUS="$TMP_DIR/vectors.json"
python3 - "$TMP_CORPUS" "$TMP_DIR/guard.json" <<'PY'
import json
import sys
from pathlib import Path
vectors = json.loads(Path("test-vectors/compatibility-profile-v1/vectors.json").read_text())
assert len(vectors) == 22
assert sum(not v["reject"] for v in vectors) == 21
victim = next(v for v in vectors if v["id"] == "v001" and not v["reject"])
# Before corruption, accepted expected values are the synthetic candidates.
# Check every vector, not just the replacement value or the first candidate.
protected = {v[key] for v in vectors for key in ("expected", "secretB64") if v[key]}
victim["expected"] = "corrupted_expected_v001"
protected.add(victim["expected"])
Path(sys.argv[1]).write_text(json.dumps(vectors))
Path(sys.argv[2]).write_text(json.dumps(sorted(protected)))
PY

check_output() {
  local consumer="$1" status="$2" output="$3" marker="$4"
  # Scan even unexpected-success and missing-marker paths before classifying them.
  if ! python3 - "$TMP_DIR/guard.json" "$output" <<'PY'
import json
import sys
from pathlib import Path
protected = json.loads(Path(sys.argv[1]).read_text())
output = Path(sys.argv[2]).read_text(errors="replace")
sys.exit(1 if any(value in output for value in protected) else 0)
PY
  then
    echo "FAIL: $consumer consumer leaked protected synthetic fixture material" >&2
    return 1
  fi
  if [ "$status" -eq 0 ]; then
    echo "FAIL: $consumer consumer unexpectedly succeeded on corrupted vector" >&2
    return 1
  fi
  if ! grep -Fq "$marker" "$output"; then
    echo "FAIL: $consumer consumer did not report the expected v001 mismatch" >&2
    return 1
  fi
  echo "$consumer: corruption detected; no complete protected fixture values in captured output."
}

rust_status=0
UPSPA_TEST_VECTORS_PATH="$TMP_CORPUS" cargo test --locked -p upspa-core --test vectors_password_encoder > "$TMP_DIR/rust.log" 2>&1 || rust_status=$?
check_output Rust "$rust_status" "$TMP_DIR/rust.log" "password mismatch for vector IDs: v001"

ts_status=0
UPSPA_TEST_VECTORS_PATH="$TMP_CORPUS" npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts > "$TMP_DIR/ts.log" 2>&1 || ts_status=$?
check_output TypeScript "$ts_status" "$TMP_DIR/ts.log" "v001: password mismatch"
echo "Corruption detection verified for both consumers."
