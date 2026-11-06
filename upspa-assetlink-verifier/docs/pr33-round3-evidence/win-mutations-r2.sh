#!/usr/bin/env bash
# Round 3 follow-up proofs in a temporary git worktree (never the working branch):
#   U    : unfixed script/validator (158b393) with the new test file from <tests-commit> copied in
#   F2a1 : classification weakened (`2) ;;` -> `[1-9]*) ;;`), evidence check kept
#   F2a2 : classification weakened AND evidence check dropped
#   F2b  : evidence check dropped
#   P158 / PFIX : scratch escape probe (uncommitted) at 158b393 and at <tests-commit>
# Usage: bash win-mutations-r2.sh <repo> <worktree-dir> <unfixed-commit> <tests-commit> <probe-file> <out-dir>
set -u
REPO="$1"; WT="$2"; UNFIXED="$3"; TESTS="$4"; PROBE="$5"; OUTDIR="$6"
export PATH="/c/Program Files/Git/bin:$PATH"
TESTFILE=upspa-assetlink-verifier/src/test/kotlin/org/upspa/catalog/ReviewRound3RegressionTest.kt
SCRIPT=upspa-assetlink-verifier/scripts/mutation-check.sh
PROBE_DEST=upspa-assetlink-verifier/src/test/kotlin/org/upspa/catalog/ScratchEscapeProbeTest.kt
XDIR=upspa-assetlink-verifier/build/test-results/test

git -C "$REPO" worktree add -q --detach "$WT" "$TESTS" || exit 90

run_case() { # run_case <id> <base-commit> <description> <mutation command> <test filter>
    local id="$1" base="$2" desc="$3" cmd="$4" filter="$5" out="$OUTDIR/mutation-$1.txt"
    git -C "$WT" checkout -q --force "$base"
    git -C "$WT" clean -qfdx -e upspa-assetlink-verifier/.gradle >/dev/null
    (cd "$WT" && eval "$cmd")
    {
        echo "=== $id: $desc ==="
        echo "Environment: Windows, $("/c/Program Files/Git/bin/bash.exe" --version | head -1), temporary worktree based on $(git -C "$WT" rev-parse HEAD)"
        echo "Mutation command: $cmd"
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
    grep -ho "PROBE.*" "$WT/$XDIR"/TEST-*.xml >> "$out" 2>/dev/null
    git -C "$WT" checkout -q --force "$base"
    git -C "$WT" clean -qfd -e upspa-assetlink-verifier/.gradle -e upspa-assetlink-verifier/build >/dev/null
    echo "# worktree restored: git status --short -> '$(git -C "$WT" status --short)'" >> "$out"
}

R3='org.upspa.catalog.ReviewRound3RegressionTest'
run_case U "$UNFIXED" "unfixed code (158b393) with the test file from $TESTS copied in" \
    "git checkout -q $TESTS -- $TESTFILE" "$R3"
run_case F2a1 "$TESTS" "classification weakened: any nonzero child status accepted (evidence check kept)" \
    "sed -i 's/^        2) ;;\r\?\$/        [1-9]*) ;;/' $SCRIPT" "$R3"
run_case F2a2 "$TESTS" "classification weakened to any nonzero AND evidence requirement dropped" \
    "sed -i -e 's/^        2) ;;\r\?\$/        [1-9]*) ;;/' -e 's/^    if \[ -z \"\$SELFTEST_REASON\" \]; then/    if false; then/' $SCRIPT" "$R3"
run_case F2b "$TESTS" "evidence requirement dropped (status classification kept)" \
    "sed -i 's/^    if \[ -z \"\$SELFTEST_REASON\" \]; then/    if false; then/' $SCRIPT" "$R3"
run_case P158 "$UNFIXED" "scratch escape probe (uncommitted) on the unfixed validator" \
    "cp '$PROBE' $PROBE_DEST" "org.upspa.catalog.ScratchEscapeProbeTest"
run_case PFIX "$TESTS" "scratch escape probe (uncommitted) on the fixed validator" \
    "cp '$PROBE' $PROBE_DEST" "org.upspa.catalog.ScratchEscapeProbeTest"

git -C "$REPO" worktree remove --force "$WT"
git -C "$REPO" worktree prune
git -C "$REPO" worktree list > "$OUTDIR/worktree-list-after-r2.txt"
cat "$OUTDIR/worktree-list-after-r2.txt"
