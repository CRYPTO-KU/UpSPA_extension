#!/usr/bin/env bash
# Mutation proofs F1, F2(a1), F2(a2), F2(b) in a temporary git worktree (never the working branch).
# Usage: bash win-mutations.sh <repo> <worktree-dir> <fix-commit> <out-dir>
set -u
REPO="$1"; WT="$2"; FIX="$3"; OUTDIR="$4"
export PATH="/c/Program Files/Git/bin:$PATH"
VALIDATOR=upspa-assetlink-verifier/src/main/kotlin/org/upspa/catalog/CatalogValidator.kt
SCRIPT=upspa-assetlink-verifier/scripts/mutation-check.sh
X=upspa-assetlink-verifier/build/test-results/test/TEST-org.upspa.catalog.ReviewRound3RegressionTest.xml

git -C "$REPO" worktree add -q --detach "$WT" "$FIX" || exit 90

run_case() { # run_case <id> <description> <mutation command>
    local id="$1" desc="$2" cmd="$3" out="$OUTDIR/mutation-$1.txt"
    git -C "$WT" checkout -q --force "$FIX"
    git -C "$WT" clean -qfdx -e upspa-assetlink-verifier/.gradle >/dev/null
    (cd "$WT" && eval "$cmd")
    {
        echo "=== Mutation proof $id: $desc ==="
        echo "Environment: Windows, $("/c/Program Files/Git/bin/bash.exe" --version | head -1), temporary worktree based on $(git -C "$WT" rev-parse HEAD)"
        echo "Mutation command: $cmd"
        echo "--- git diff (mutation) ---"
        git -C "$WT" --no-pager diff --ignore-cr-at-eol
        echo "\$ ./gradlew test --rerun --tests 'org.upspa.catalog.ReviewRound3RegressionTest' --no-daemon"
    } > "$out" 2>&1
    (cd "$WT/upspa-assetlink-verifier" && ./gradlew test --rerun --tests 'org.upspa.catalog.ReviewRound3RegressionTest' --no-daemon) >> "$out" 2>&1
    echo "# gradle exit code: $?" >> "$out"
    echo "# XML totals: $(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$WT/$X" | head -1)" >> "$out"
    echo "# failing test cases:" >> "$out"
    awk '/<testcase /{match($0,/name="[^"]*"/); n=substr($0,RSTART+6,RLENGTH-7)} /<failure/{print "  " n}' "$WT/$X" >> "$out"
    git -C "$WT" checkout -q --force "$FIX"
    echo "# worktree restored: git status --short -> '$(git -C "$WT" status --short --untracked-files=no)'" >> "$out"
}

run_case F1 "remove the escaped-control check (CatalogValidator.kt restored to its pre-fix version)" \
    "git checkout -q $FIX~2 -- $VALIDATOR"
run_case F2a1 "classification weakened: any nonzero child status accepted (evidence check kept)" \
    "sed -i 's/^        2) ;;\r\?\$/        [1-9]*) ;;/' $SCRIPT"
run_case F2a2 "classification weakened to any nonzero AND evidence requirement dropped (pre-fix semantics)" \
    "sed -i -e 's/^        2) ;;\r\?\$/        [1-9]*) ;;/' -e 's/^    if \[ -z \"\$SELFTEST_REASON\" \]; then/    if false; then/' $SCRIPT"
run_case F2b "evidence requirement dropped (status classification kept)" \
    "sed -i 's/^    if \[ -z \"\$SELFTEST_REASON\" \]; then/    if false; then/' $SCRIPT"

git -C "$REPO" worktree remove --force "$WT"
git -C "$REPO" worktree prune
git -C "$REPO" worktree list > "$OUTDIR/worktree-list-after.txt"
cat "$OUTDIR/worktree-list-after.txt"
