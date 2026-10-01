#!/usr/bin/env bash
# Demonstrates that the Kotlin encoder conformance run FAILS when an accepted vector is corrupted.
#
# Works on a temporary copy only; the shared corpus is never modified. Prints vector IDs only.
#
# Usage: scripts/demo_corrupted_vector.sh
# Exit 0 = the conformance run failed as it should; exit 1 = it wrongly passed.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPUS="$REPO_ROOT/test-vectors/compatibility-profile-v1/vectors.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
COPY="$TMP/vectors.json"

CORRUPTED_ID="$(python3 - "$CORPUS" "$COPY" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
vectors = json.load(open(src, encoding="utf-8"))
v = next(v for v in vectors if not v["reject"])
last = v["expected"][-1]
v["expected"] = v["expected"][:-1] + ("B" if last == "A" else "A")
json.dump(vectors, open(dst, "w", encoding="utf-8"), indent=2)
print(v["id"])
PY
)"
echo "==> corrupted accepted vector $CORRUPTED_ID in a temporary copy"

# Gradle runs on the JVM, which needs a native path on Windows (C:/..., not Git Bash's /tmp/...).
COPY_FOR_GRADLE="$COPY"
command -v cygpath >/dev/null 2>&1 && COPY_FOR_GRADLE="$(cygpath -m "$COPY")"

cd "$REPO_ROOT/apps/android"
OUTPUT="$TMP/gradle.log"
if ./gradlew :ffi:cleanTestDebugUnitTest :ffi:testDebugUnitTest \
    --tests 'com.upspa.mobile.ffi.EncoderConformanceTest' \
    -PupspaVectorCorpus="$COPY_FOR_GRADLE" --console=plain > "$OUTPUT" 2>&1; then
  echo "ERROR: conformance passed on a corrupted corpus" >&2
  exit 1
fi
# Must fail for the right reason: the corrupted ID is reported, not a build or setup error.
# Checked in both the console output and the JUnit XML report, so it does not depend on
# Gradle's console formatting.
REPORT_DIR="$REPO_ROOT/apps/android/ffi/build/test-results/testDebugUnitTest"
if ! grep -q "mismatch for IDs: \[$CORRUPTED_ID\]" "$OUTPUT" \
   && ! grep -qs "mismatch for IDs: \[$CORRUPTED_ID\]" "$REPORT_DIR"/TEST-*EncoderConformanceTest*.xml; then
  echo "ERROR: Gradle failed, but not with the expected vector mismatch. Output:" >&2
  tail -n 40 "$OUTPUT" >&2
  exit 1
fi
echo "==> conformance failed as expected, reporting only vector ID $CORRUPTED_ID"
