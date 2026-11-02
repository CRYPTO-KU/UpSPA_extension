#!/usr/bin/env bash
# Mutation proof (c): in the WSL ext4 clone (a copy, not the working branch), revert the self-test child
# launch to the direct "$BASH_SOURCE" form and run ReviewRound3RegressionTest.
# Usage: bash wsl-mutation-c.sh <fix-commit> <out-file>
set -u
COMMIT="$1"
OUT="$2"
CLONE="$HOME/upspa-r3-wsl"
export JAVA_HOME="$(ls -d "$HOME"/jdk17/jdk-17*)"
export PATH="$JAVA_HOME/bin:$PATH"
cd "$CLONE" || exit 91
git checkout -q --force "$COMMIT" || exit 92
cd upspa-assetlink-verifier || exit 93
S=scripts/mutation-check.sh
OLD='    SELFTEST_OUTPUT="$("${BASH:-bash}" "$ABS_SCRIPT" --inject-compile-fault 2>&1)"'
NEW='    SELFTEST_OUTPUT="$("$BASH_SOURCE" --inject-compile-fault 2>&1)"'
{
    echo "=== Mutation proof (c): WSL2 $(uname -sr), clone $CLONE, base commit $(git rev-parse HEAD) ==="
    echo "Launch line before: $(grep -n -F -- "$OLD" "$S")"
    if [ "$(grep -c -F -- "$OLD" "$S")" -ne 1 ]; then echo "FATAL: launch line not found exactly once"; exit 1; fi
    MUTATED="$(awk -v old="$OLD" -v new="$NEW" '$0 == old { print new; next } { print }' "$S")"
    printf '%s\n' "$MUTATED" > "$S"
    echo "Launch line after : $(grep -n -F -- "$NEW" "$S")"
    echo "Script mode after mutation: $(stat -c '%a %A' "$S")"
    echo "--- git diff (mutation) ---"
    git --no-pager diff -- "$S"
    echo "\$ ./gradlew test --rerun --tests '*ReviewRound3RegressionTest*' --no-daemon"
} > "$OUT" 2>&1
./gradlew test --rerun --tests '*ReviewRound3RegressionTest*' --no-daemon >> "$OUT" 2>&1
STATUS=$?
echo "# gradle exit code: $STATUS" >> "$OUT"
X=build/test-results/test/TEST-org.upspa.catalog.ReviewRound3RegressionTest.xml
echo "# XML totals: $(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$X" | head -1)" >> "$OUT"
echo "# failing test cases:" >> "$OUT"
awk '/<testcase /{match($0,/name="[^"]*"/); n=substr($0,RSTART+6,RLENGTH-7)} /<failure/{print "  " n}' "$X" >> "$OUT"
echo "# mode-644 positive test entry from XML:" >> "$OUT"
grep -A8 'mode 644' "$X" | grep -v '^\s*at ' >> "$OUT"
git checkout -q -- "$S"
echo "# restored clone: git status --short -> '$(git status --short)'" >> "$OUT"
exit 0
