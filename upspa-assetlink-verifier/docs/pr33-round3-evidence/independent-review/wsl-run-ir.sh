#!/usr/bin/env bash
# Runs ReviewRound3RegressionTest and the independent review test classes in a WSL ext4 clone of the
# local repository at a given commit.
# Usage: bash wsl-run-ir.sh <src-repo> <commit> <out-file>
set -u
SRC_REPO="$1"
COMMIT="$2"
OUT="$3"
CLONE="$HOME/upspa-r3-wsl"
export JAVA_HOME="$(ls -d "$HOME"/jdk17/jdk-17*)"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d "$CLONE/.git" ]; then
    git -c safe.directory='*' clone -q --no-checkout "$SRC_REPO" "$CLONE" || exit 90
fi
cd "$CLONE" || exit 91
git -c safe.directory='*' fetch -q "$SRC_REPO" "$COMMIT" 2>/dev/null || true
git checkout -q --force "$COMMIT" || exit 92

FILTERS=(--tests '*ReviewRound3RegressionTest*' --tests '*IndependentReviewValidatorRegressionTest*' --tests '*IndependentReviewScriptRegressionTest*')
{
    echo "=== Environment: WSL2 $(uname -sr); clone at $CLONE (filesystem: $(stat -f -c %T "$CLONE")) ==="
    echo "Commit: $(git rev-parse HEAD) ($(git log -1 --format=%s))"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "bash: $(command -v bash) ($(bash --version | head -1))"
    echo "scripts/mutation-check.sh mode in clone: $(stat -c '%a %A' upspa-assetlink-verifier/scripts/mutation-check.sh); git index mode: $(git ls-files -s upspa-assetlink-verifier/scripts/mutation-check.sh | cut -d' ' -f1)"
    echo "\$ cd upspa-assetlink-verifier && ./gradlew test --rerun ${FILTERS[*]} --no-daemon"
} > "$OUT"
cd upspa-assetlink-verifier || exit 93
chmod +x gradlew
./gradlew test --rerun "${FILTERS[@]}" --no-daemon >> "$OUT" 2>&1
STATUS=$?
echo "# gradle exit code: $STATUS" >> "$OUT"
for X in build/test-results/test/TEST-*.xml; do
    echo "# XML $(basename "$X"): $(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$X" | head -1)" >> "$OUT"
done
exit "$STATUS"
