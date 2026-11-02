#!/usr/bin/env bash
# Independent review follow-up proofs in a temporary git worktree (never the working branch).
# Each case removes one new check, runs the named test class and records the failing test cases.
# Usage: bash win-mutations-ir.sh <repo> <worktree-dir> <commit> <mutate.py> <out-dir>
set -u
REPO="$1"; WT="$2"; COMMIT="$3"; MUTATE="$4"; OUTDIR="$5"
export PATH="/c/Program Files/Git/bin:$PATH"
VALIDATOR=upspa-assetlink-verifier/src/main/kotlin/org/upspa/catalog/CatalogValidator.kt
SCRIPT=upspa-assetlink-verifier/scripts/mutation-check.sh
XDIR=upspa-assetlink-verifier/build/test-results/test
VAL_TESTS='org.upspa.catalog.IndependentReviewValidatorRegressionTest'
SCRIPT_TESTS='org.upspa.catalog.IndependentReviewScriptRegressionTest'

git -C "$REPO" worktree add -q --detach "$WT" "$COMMIT" || exit 90

run_case() { # run_case <id> <description> <file> <old> <new> <test filter>
    local id="$1" desc="$2" file="$3" old="$4" new="$5" filter="$6" out="$OUTDIR/mutation-$1.txt"
    git -C "$WT" checkout -q --force "$COMMIT"
    git -C "$WT" clean -qfdx -e upspa-assetlink-verifier/.gradle >/dev/null
    {
        echo "=== $id: $desc ==="
        echo "Environment: Windows, $("/c/Program Files/Git/bin/bash.exe" --version | head -1), temporary worktree based on $(git -C "$WT" rev-parse HEAD)"
        echo "Mutation: replace exactly one occurrence in $file"
        python -I "$MUTATE" "$WT/$file" "$old" "$new" 2>&1 || echo "MUTATION NOT APPLIED"
        echo "--- git status --short (worktree) ---"
        git -C "$WT" status --short
        echo "--- git diff HEAD (mutation, CR-at-EOL ignored) ---"
        git -C "$WT" --no-pager diff --ignore-cr-at-eol HEAD
        echo "\$ ./gradlew test --rerun --tests '$filter' --no-daemon"
    } > "$out" 2>&1
    (cd "$WT/upspa-assetlink-verifier" && ./gradlew test --rerun --tests "$filter" --no-daemon) >> "$out" 2>&1
    echo "# gradle exit code: $?" >> "$out"
    for x in "$WT/$XDIR"/TEST-*.xml; do
        echo "# XML $(basename "$x"): $(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$x" | head -1)" >> "$out"
    done
    echo "# failing test cases:" >> "$out"
    awk '/<testcase /{match($0,/name="[^"]*"/); n=substr($0,RSTART+6,RLENGTH-7)} /<failure/{print "  " n}' "$WT/$XDIR"/TEST-*.xml >> "$out"
    git -C "$WT" checkout -q --force "$COMMIT"
    git -C "$WT" clean -qfd -e upspa-assetlink-verifier/.gradle -e upspa-assetlink-verifier/build >/dev/null
    echo "# worktree restored: git status --short -> '$(git -C "$WT" status --short)'" >> "$out"
}

run_case A "keyword check removed (non-lowercase true/false/null no longer rejected)" "$VALIDATOR" \
    'if (lastToken in KEYWORD_VALUE_PREDECESSORS && isNonLowercaseKeyword(utf8Bytes, i, end)) {' \
    'if (false) {' "$VAL_TESTS"
run_case B "escape allow-list removed (backslash-apostrophe no longer rejected)" "$VALIDATOR" \
    'if (unsigned !in VALID_ESCAPE_BYTES) {' \
    'if (false) {' "$VAL_TESTS"
run_case C1 "SIGTERM handling removed (TERM handled by cleanup again)" "$SCRIPT" \
    "trap 'on_signal TERM 143' TERM" \
    'trap cleanup TERM' "$SCRIPT_TESTS"
run_case C2 "cleanup exit-code handling removed (set +e dropped from cleanup)" "$SCRIPT" \
    'instead of failing under `set -e`.\n    set +e\n' \
    'instead of failing under `set -e`.\n' "$SCRIPT_TESTS"
run_case C3 "unset CDPATH removed" "$SCRIPT" \
    'unset CDPATH\n' \
    '' "$SCRIPT_TESTS"
run_case C4 "integer guard weakened back to the empty-or-zero check" "$SCRIPT" \
    'if ! [[ "$TESTS_EXECUTED" =~ ^[0-9]+$ ]] || [ "$TESTS_EXECUTED" -le 0 ]; then' \
    'if [ -z "$TESTS_EXECUTED" ] || [ "$TESTS_EXECUTED" -le 0 ]; then' "$SCRIPT_TESTS"
run_case C5 "fresh-baseline flags removed from the baseline invocation" "$SCRIPT" \
    'BASELINE_OUTPUT=$(./gradlew test --rerun-tasks --no-build-cache 2>&1)' \
    'BASELINE_OUTPUT=$(./gradlew test 2>&1)' "$SCRIPT_TESTS"

git -C "$REPO" worktree remove --force "$WT"
git -C "$REPO" worktree prune
git -C "$REPO" worktree list > "$OUTDIR/worktree-list-after-ir-proofs.txt"
