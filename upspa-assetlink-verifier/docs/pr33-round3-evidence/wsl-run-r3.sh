#!/usr/bin/env bash
# Runs ReviewRound3RegressionTest in a WSL ext4 clone of the local repository at a given commit.
# Usage: bash wsl-run-r3.sh <commit> <out-file>
set -u
COMMIT="$1"
OUT="$2"
SRC_REPO=<WIN_HOME>/UpSPA_extension_r3
CLONE="$HOME/upspa-r3-wsl"
export JAVA_HOME="$(ls -d "$HOME"/jdk17/jdk-17*)"
export PATH="$JAVA_HOME/bin:$PATH"

if [ ! -d "$CLONE/.git" ]; then
    git -c safe.directory='*' clone -q --no-checkout "$SRC_REPO" "$CLONE" || exit 90
fi
cd "$CLONE" || exit 91
git -c safe.directory='*' fetch -q "$SRC_REPO" "$COMMIT" 2>/dev/null || true
git checkout -q --force "$COMMIT" || exit 92

{
    echo "=== Environment: WSL2 $(uname -sr); clone at $CLONE (filesystem: $(stat -f -c %T "$CLONE")) ==="
    echo "Commit: $(git rev-parse HEAD) ($(git log -1 --format=%s))"
    echo "java: $(java -version 2>&1 | head -1)"
    echo "bash: $(command -v bash) ($(bash --version | head -1))"
    echo "scripts/mutation-check.sh mode in clone: $(stat -c '%a %A' upspa-assetlink-verifier/scripts/mutation-check.sh); git index mode: $(git ls-files -s upspa-assetlink-verifier/scripts/mutation-check.sh | cut -d' ' -f1)"
    echo "\$ cd upspa-assetlink-verifier && ./gradlew test --tests '*ReviewRound3RegressionTest*' --no-daemon"
} > "$OUT"
cd upspa-assetlink-verifier || exit 93
chmod +x gradlew
./gradlew test --tests '*ReviewRound3RegressionTest*' --no-daemon >> "$OUT" 2>&1
STATUS=$?
echo "# gradle exit code: $STATUS" >> "$OUT"
X=build/test-results/test/TEST-org.upspa.catalog.ReviewRound3RegressionTest.xml
if [ -f "$X" ]; then
    echo "# XML totals: $(grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$X" | head -1)" >> "$OUT"
    echo "# mode-644 positive test entry from XML:" >> "$OUT"
    grep -A6 'mode 644' "$X" | grep -v '^\s*at ' >> "$OUT"
fi
exit "$STATUS"
