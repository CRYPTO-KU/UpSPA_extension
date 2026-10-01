#!/usr/bin/env bash
# Mutation check script for upspa-assetlink-verifier
# Proves that IdentityAdversarialSuite detects weakening/removal of security controls.
# Runs ONLY in an isolated temporary directory; NEVER alters the real working tree.

set -euo pipefail

REAL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REAL_ROOT"

echo "=== Identity Adversarial Suite: Mutation Testing ==="

# 1. Record snapshot of real working tree
BEFORE_STATUS="$(git status --porcelain -- .)"

# 2. Create isolated throw-away copy with trap
TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t 'mutation')"
echo "Created temporary isolation directory: $TMP_DIR"

cleanup() {
    local exit_code=$?
    echo "Cleaning up temporary isolation directory..."
    if [ -d "$TMP_DIR" ]; then
        (cd "$TMP_DIR" && ./gradlew --stop >/dev/null 2>&1 || true)
        rm -rf "$TMP_DIR"
    fi
    exit "$exit_code"
}
trap cleanup EXIT INT TERM

# 3. Copy module assets (excluding build/ and .gradle/)
echo "Copying repository files to temporary directory..."
cp -a "$REAL_ROOT/build.gradle.kts" "$TMP_DIR/"
cp -a "$REAL_ROOT/settings.gradle.kts" "$TMP_DIR/"
cp -a "$REAL_ROOT/gradlew" "$TMP_DIR/"
cp -a "$REAL_ROOT/gradlew.bat" "$TMP_DIR/"
cp -a "$REAL_ROOT/gradle" "$TMP_DIR/"
cp -a "$REAL_ROOT/src" "$TMP_DIR/"
chmod +x "$TMP_DIR/gradlew"

# 4. Run baseline test suite in temporary copy
echo "Running baseline test suite in temporary copy..."
cd "$TMP_DIR"
BASELINE_OUTPUT=$(./gradlew test 2>&1)
BASELINE_STATUS=$?

if [ $BASELINE_STATUS -ne 0 ]; then
    echo "FATAL: Baseline build/test failed in temporary copy!"
    echo "$BASELINE_OUTPUT"
    exit 1
fi

BASELINE_COUNT=$(echo "$BASELINE_OUTPUT" | grep -o "[0-9]\+ tests completed" | tail -n 1 || echo "all passed")
echo "Baseline PASSED: $BASELINE_COUNT"

# 5. Define mutations
# Format: ID | FILE | GREP_PATTERN | SED_PATTERN | REPLACEMENT | DESCRIPTION | EXPECTED_CATEGORY
MUTATIONS=(
    "M1|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|target\.packageName == appSigningInfo\.packageName|s/target\.packageName == appSigningInfo\.packageName/target.packageName.equals(appSigningInfo.packageName, ignoreCase = true)/|Package-name comparison weakened to case-insensitive|PACKAGE_MISMATCH"
    "M2|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|requestedOrigin != evidence\.sourceOrigin|s/requestedOrigin != evidence\.sourceOrigin/requestedOrigin.host != evidence.sourceOrigin.host/|Origin-binding check weakened to compare host only|ORIGIN_BOUNDARY / REQUESTED_ENROLLED_BINDING"
    "M3|src/main/kotlin/org/upspa/assetlinks/model/Origin.kt| && port == other\.port|s/ && port == other\.port//|Origin.equals weakened to ignore port|ORIGIN_BOUNDARY / REQUESTED_ENROLLED_BINDING"
    "M4|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|normalizedClaimedFingerprints\.contains(normalizedFp)|s/normalizedClaimedFingerprints\.contains(normalizedFp)/normalizedClaimedFingerprints.any { it.startsWith(normalizedFp.take(12)) }/|Certificate matching weakened to prefix match (first 12 hex chars)|CERTIFICATE_MISMATCH"
    "M5|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|appSigningInfo\.hasMultipleSigners|s/appSigningInfo\.hasMultipleSigners/false/|Multi-signer rejection disabled|MULTIPLE_SIGNERS"
    "M6|src/main/kotlin/org/upspa/assetlinks/crypto/CertificateUtils.kt| && bis\.available() == 0|s/ && bis\.available() == 0//|Certificate trailing-bytes check disabled in isValidX509Certificate|MALFORMED_CERTIFICATE"
    "M7|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|statementRelations\.contains(requiredRelation)|s/statementRelations\.contains(requiredRelation)/statementRelations.isNotEmpty()/|Relation check weakened to non-empty relation list|REQUESTED_ENROLLED_BINDING"
    "M8|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|!origin\.isHttps && !allowInsecureHttp|s/!origin\.isHttps/false/|Non-HTTPS origin rejection disabled|ORIGIN_BOUNDARY"
)

REPORT_ROWS=()
SURVIVED_COUNT=0

for item in "${MUTATIONS[@]}"; do
    IFS='|' read -r MID MFILE MPATTERN MSED MDESC MCAT <<< "$item"
    echo ""
    echo "--- Testing Mutation $MID: $MDESC ---"

    # a. Restore file fresh from real repository
    cp "$REAL_ROOT/$MFILE" "$TMP_DIR/$MFILE"

    # b. Verify pattern occurs exactly once
    COUNT=$(grep -c "$MPATTERN" "$TMP_DIR/$MFILE" || true)
    if [ "$COUNT" -ne 1 ]; then
        echo "FATAL: Pattern '$MPATTERN' occurs $COUNT times in $MFILE (expected 1)"
        exit 1
    fi

    # c. Apply minimal sed replacement
    sed -i "$MSED" "$TMP_DIR/$MFILE"

    # d. Verify mutation changed the file
    if cmp -s "$REAL_ROOT/$MFILE" "$TMP_DIR/$MFILE"; then
        echo "FATAL: Mutation $MID did not alter $MFILE!"
        exit 1
    fi

    # e. Run adversarial suite in copy (MUST fail)
    set +e
    MUTATION_RUN_OUT=$(./gradlew test --rerun-tasks --tests '*IdentityAdversarialSuite*' 2>&1)
    MUTATION_RUN_STATUS=$?
    set -e

    if [ $MUTATION_RUN_STATUS -eq 0 ]; then
        echo "FAIL: Mutation $MID SURVIVED! Adversarial suite stayed green."
        REPORT_ROWS+=("| \`$MID\` | \`$MFILE\` | $MDESC | **NO (SURVIVED)** | *None* |")
        SURVIVED_COUNT=$((SURVIVED_COUNT + 1))
    else
        set +e
        FAILING_IDS=$(echo "$MUTATION_RUN_OUT" | grep -E "IdentityAdversarialSuite > testIdentityCase.*FAILED" | sed -E 's/.*> ([A-Za-z0-9_]+) FAILED/\1/' | tr '\n' ',' | sed 's/,$//' | sed 's/,/, /g')
        if [ -z "$FAILING_IDS" ]; then
            FAILING_IDS=$(echo "$MUTATION_RUN_OUT" | grep -E "IdentityAdversarialSuite > .*FAILED" | sed -E 's/.*> ([A-Za-z0-9_]+)(\(\))? FAILED/\1/' | tr '\n' ',' | sed 's/,$//' | sed 's/,/, /g')
        fi
        if [ -z "$FAILING_IDS" ]; then
            FAILING_IDS="Suite failed"
        fi
        set -e
        echo "PASS: Mutation $MID KILLED! Failing cases: $FAILING_IDS"
        REPORT_ROWS+=("| \`$MID\` | \`$MFILE\` | $MDESC | **YES (KILLED)** | \`$FAILING_IDS\` |")
    fi

    # Clean up file in temporary tree
    cp "$REAL_ROOT/$MFILE" "$TMP_DIR/$MFILE"
done

# 6. Verify real working tree was NEVER touched
cd "$REAL_ROOT"
AFTER_STATUS="$(git status --porcelain -- .)"
if [ "$BEFORE_STATUS" = "$AFTER_STATUS" ]; then
    WORKING_TREE_UNCHANGED="yes"
else
    WORKING_TREE_UNCHANGED="no"
    echo "FATAL: Real working tree was modified during mutation check!"
    exit 1
fi

# 7. Write report in real module under build/reports/
mkdir -p "$REAL_ROOT/build/reports"
REPORT_FILE="$REAL_ROOT/build/reports/mutation-check.md"

cat <<EOF > "$REPORT_FILE"
# Mutation Check Report

Generated on: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
Baseline test outcome: **PASSED** ($BASELINE_COUNT)
Working tree unchanged: **$WORKING_TREE_UNCHANGED**

## Mutation Results

| Mutation ID | File | Description | Killed | Failing Case IDs |
| :--- | :--- | :--- | :--- | :--- |
$(for row in "${REPORT_ROWS[@]}"; do echo "$row"; done)

## Summary
- Total mutations tested: **${#MUTATIONS[@]}**
- Mutations killed: **$((${#MUTATIONS[@]} - SURVIVED_COUNT))**
- Mutations survived: **$SURVIVED_COUNT**
EOF

echo ""
echo "Mutation check report generated at: $REPORT_FILE"
cat "$REPORT_FILE"

if [ $SURVIVED_COUNT -gt 0 ]; then
    echo "FATAL: $SURVIVED_COUNT mutation(s) survived!"
    exit 1
fi

echo ""
echo "SUCCESS: All mutations were killed by the Identity Adversarial Suite!"
exit 0
