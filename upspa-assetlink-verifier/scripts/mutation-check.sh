#!/usr/bin/env bash
# Mutation check script for upspa-assetlink-verifier
# Proves that IdentityAdversarialSuite detects weakening/removal of security controls.
# Runs ONLY in an isolated temporary directory; NEVER alters the real working tree.

set -euo pipefail

REAL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REAL_ROOT"

# Self-test mode: injects a compile fault into a sub-execution and verifies it exits non-zero
if [ "${1:-}" = "--selftest-compile-fault" ]; then
    echo "=== Mutation Checker Self-Test Mode ==="
    echo "Invoking mutation checker with an injected compile fault..."
    set +e
    "$BASH_SOURCE" --inject-compile-fault
    SELFTEST_STATUS=$?
    set -e

    if [ $SELFTEST_STATUS -ne 0 ]; then
        echo "Self-test PASSED: mutation checker rejected compile fault with non-zero exit code ($SELFTEST_STATUS)."
        exit 0
    else
        echo "Self-test FAILED: mutation checker exited with code 0 despite injected compile fault!"
        exit 1
    fi
fi

INJECT_COMPILE_FAULT=false
if [ "${1:-}" = "--inject-compile-fault" ]; then
    INJECT_COMPILE_FAULT=true
    echo "=== Running mutation checker with deliberate compile fault injection ==="
fi

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
cp -a "$REAL_ROOT/scripts" "$TMP_DIR/"
cp -a "$REAL_ROOT/docs" "$TMP_DIR/"
chmod +x "$TMP_DIR/gradlew"

# 4. Run baseline test suite in temporary copy
echo "Running baseline test suite in temporary copy..."
cd "$TMP_DIR"
set +e
BASELINE_OUTPUT=$(./gradlew test 2>&1)
BASELINE_STATUS=$?
set -e

if [ $BASELINE_STATUS -ne 0 ]; then
    echo "FATAL: Baseline build/test failed in temporary copy!"
    echo "$BASELINE_OUTPUT" | tail -n 50
    exit 1
fi

BASELINE_COUNT=$(echo "$BASELINE_OUTPUT" | grep -o "[0-9]\+ tests completed" | tail -n 1 || echo "all passed")
echo "Baseline PASSED: $BASELINE_COUNT"

# 5. Define mutations
# Format: ID | FILE | GREP_PATTERN | SED_PATTERN | DESCRIPTION
MUTATIONS=(
    "M1|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|target\.packageName == appSigningInfo\.packageName|s/target\.packageName == appSigningInfo\.packageName/target.packageName.equals(appSigningInfo.packageName, ignoreCase = true)/|Package-name comparison weakened to case-insensitive"
    "M2|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|requestedOrigin != evidence\.sourceOrigin|s/requestedOrigin != evidence\.sourceOrigin/requestedOrigin.host != evidence.sourceOrigin.host/|Origin-binding check weakened to compare host only"
    "M3|src/main/kotlin/org/upspa/assetlinks/model/Origin.kt| && port == other\.port|s/ && port == other\.port//|Origin.equals weakened to ignore port"
    "M4|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|normalizedClaimedFingerprints\.contains(normalizedFp)|s/normalizedClaimedFingerprints\.contains(normalizedFp)/normalizedClaimedFingerprints.any { it.startsWith(normalizedFp.take(12)) }/|Certificate matching weakened to prefix match (first 12 hex chars)"
    "M5|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|appSigningInfo\.hasMultipleSigners|s/appSigningInfo\.hasMultipleSigners/false/|Multi-signer rejection disabled"
    "M6|src/main/kotlin/org/upspa/assetlinks/crypto/CertificateUtils.kt| && bis\.available() == 0|s/ && bis\.available() == 0//|Certificate trailing-bytes check disabled in isValidX509Certificate"
    "M7|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|statementRelations\.contains(requiredRelation)|s/statementRelations\.contains(requiredRelation)/statementRelations.isNotEmpty()/|Relation check weakened to non-empty relation list"
    "M8|src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt|!origin\.isHttps && !allowInsecureHttp|s/!origin\.isHttps/false/|Non-HTTPS origin rejection disabled"
)

REPORT_ROWS=()
SURVIVED_COUNT=0

for item in "${MUTATIONS[@]}"; do
    IFS='|' read -r MID MFILE MPATTERN MSED MDESC <<< "$item"
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

    # c. Apply mutation (portable sed -i using .bak) or inject compile fault
    if [ "$INJECT_COMPILE_FAULT" = true ] && [ "$MID" = "M1" ]; then
        echo "Injecting deliberate Kotlin compilation fault into $MFILE for $MID..."
        printf "\n// SYNTAX_ERROR_INJECTED_FOR_COMPILE_FAULT\nval illegalSyntaxInKotlin: Int = \"not an int\"\n" >> "$TMP_DIR/$MFILE"
    else
        sed -i.bak "$MSED" "$TMP_DIR/$MFILE" && rm -f "$TMP_DIR/$MFILE.bak"
    fi

    # d. Verify mutation changed the file
    if cmp -s "$REAL_ROOT/$MFILE" "$TMP_DIR/$MFILE"; then
        echo "FATAL: Mutation $MID did not alter $MFILE!"
        exit 1
    fi

    # e. Clean results directory before test run
    rm -rf "$TMP_DIR/build/test-results"

    # f. Check compilation first: a mutation MUST compile
    set +e
    COMPILE_OUT=$(./gradlew compileKotlin compileTestKotlin 2>&1)
    COMPILE_STATUS=$?
    set -e

    if [ $COMPILE_STATUS -ne 0 ]; then
        echo "FATAL: Mutation $MID failed to compile! Compilation errors are NOT killed mutations."
        echo "$COMPILE_OUT" | tail -n 25
        exit 2
    fi

    # g. Run adversarial suite in copy
    set +e
    MUTATION_RUN_OUT=$(./gradlew test --rerun-tasks --tests '*IdentityAdversarialSuite*' 2>&1)
    MUTATION_RUN_STATUS=$?
    set -e

    # h. Inspect fresh XML test results
    XML_FILE="$TMP_DIR/build/test-results/test/TEST-org.upspa.assetlinks.adversarial.IdentityAdversarialSuite.xml"
    if [ ! -f "$XML_FILE" ]; then
        echo "FATAL: Mutation $MID test run did not generate results XML at $XML_FILE"
        echo "$MUTATION_RUN_OUT" | tail -n 25
        exit 3
    fi

    TESTS_EXECUTED=$(grep '<testsuite ' "$XML_FILE" | sed -E 's/.*tests="([0-9]+)".*/\1/' || echo "0")
    if [ -z "$TESTS_EXECUTED" ] || [ "$TESTS_EXECUTED" -le 0 ]; then
        echo "FATAL: Mutation $MID test run reported 0 executed tests in $XML_FILE"
        exit 4
    fi

    # i. Parse failing test case names from XML
    FAILING_CASES_LIST=$(awk '
        /<testcase/ {
            name = ""
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^name=/) {
                    n = $i
                    sub(/^name="/, "", n)
                    sub(/".*$/, "", n)
                    name = n
                }
            }
            if ($0 ~ /\/>/) {
                name = ""
            }
        }
        /<failure/ || /<error/ {
            if (name != "") {
                print name
                name = ""
            }
        }
    ' "$XML_FILE")

    # j. Check against expected killing case IDs from docs/mutation-check.md
    case "$MID" in
        M1) EXPECTED_FAILURES="PACKAGE_MISMATCH_CASE_DIFFERENCE" ;;
        M2) EXPECTED_FAILURES="ORIGIN_BOUNDARY_DIFFERENT_PORT REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_PORT REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_SCHEME" ;;
        M3) EXPECTED_FAILURES="ORIGIN_BOUNDARY_DIFFERENT_PORT REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_PORT" ;;
        M4) EXPECTED_FAILURES="CERTIFICATE_MISMATCH_ONE_BYTE_DIFFERENCE CERTIFICATE_MISMATCH_PREFIX_DIGEST" ;;
        M5) EXPECTED_FAILURES="MULTIPLE_SIGNERS_ONE_AUTHORIZED MULTIPLE_SIGNERS_BOTH_AUTHORIZED" ;;
        M6) EXPECTED_FAILURES="MALFORMED_CERT_TRAILING_BYTES" ;;
        M7) EXPECTED_FAILURES="REQUESTED_ENROLLED_BINDING_MISSING_RELATION" ;;
        M8) EXPECTED_FAILURES="ORIGIN_BOUNDARY_NON_HTTPS_REJECTION" ;;
        *) echo "FATAL: Unknown mutation ID $MID"; exit 1 ;;
    esac

    if [ -z "$FAILING_CASES_LIST" ]; then
        echo "FAIL: Mutation $MID SURVIVED! Adversarial suite stayed green."
        REPORT_ROWS+=("| \`$MID\` | \`$MFILE\` | $MDESC | **NO (SURVIVED)** | *None* |")
        SURVIVED_COUNT=$((SURVIVED_COUNT + 1))
    else
        EXPECTED_MATCH_COUNT=0
        UNEXPECTED_FAILURES=()

        while IFS= read -r fcase; do
            [ -z "$fcase" ] && continue
            matched=0
            for exp in $EXPECTED_FAILURES; do
                if [ "$fcase" = "$exp" ]; then
                    matched=1
                    EXPECTED_MATCH_COUNT=$((EXPECTED_MATCH_COUNT + 1))
                    break
                fi
            done
            if [ $matched -eq 0 ]; then
                UNEXPECTED_FAILURES+=("$fcase")
            fi
        done <<< "$FAILING_CASES_LIST"

        if [ $EXPECTED_MATCH_COUNT -eq 0 ]; then
            echo "FATAL: Mutation $MID failures ($FAILING_CASES_LIST) did not contain any expected case ID ($EXPECTED_FAILURES)!"
            exit 5
        fi

        if [ ${#UNEXPECTED_FAILURES[@]} -gt 0 ]; then
            echo "FATAL: Mutation $MID produced unexpected failures outside expected set: ${UNEXPECTED_FAILURES[*]}"
            exit 6
        fi

        FAILING_IDS=$(echo "$FAILING_CASES_LIST" | tr '\n' ',' | sed 's/,$//' | sed 's/,/, /g')
        echo "PASS: Mutation $MID KILLED! ($TESTS_EXECUTED tests executed, failing cases: $FAILING_IDS)"
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
