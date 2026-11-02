#!/usr/bin/env bash
# Plain-bash harness (no Gradle, no JDK) that mirrors the Finding 2 positive test in
# ReviewRound3RegressionTest: same fixture files, same assertions. Evidence only; not committed.
# Usage: bash finding2-wsl-fixture-harness.sh <path-to-real-mutation-check.sh> <mode: 644|755>
set -u

REAL_SCRIPT="$1"
MODE="$2"
MARKER="SYNTAX_ERROR_INJECTED_FOR_COMPILE_FAULT"
VERIFIER_PATH="src/main/kotlin/org/upspa/assetlinks/verifier/PureAssetLinkVerifier.kt"

ROOT="$(mktemp -d -t mutation-selftest-fixture.XXXXXX)"
trap 'rm -rf "$ROOT"' EXIT

mkdir -p "$ROOT/scripts" "$ROOT/gradle" "$ROOT/docs" "$ROOT/$(dirname "$VERIFIER_PATH")"
tr -d '\r' < "$REAL_SCRIPT" > "$ROOT/scripts/mutation-check.sh"
printf '// Synthetic fixture: no real Gradle build.\n' > "$ROOT/build.gradle.kts"
printf 'rootProject.name = "mutation-selftest-fixture"\n' > "$ROOT/settings.gradle.kts"
printf '@rem Synthetic fixture stub; never executed.\r\nexit /b 1\r\n' > "$ROOT/gradlew.bat"
printf 'Synthetic mutation-check self-test fixture.\n' > "$ROOT/docs/README.md"
cat > "$ROOT/$VERIFIER_PATH" <<'EOF'
package org.upspa.assetlinks.verifier

// Synthetic fixture for the mutation-check self-test. Not production code.
object FixturePackageCheck {
    fun matches(target: Target, appSigningInfo: Info): Boolean =
        target.packageName == appSigningInfo.packageName
}
EOF
cat > "$ROOT/gradlew" <<EOF
#!/usr/bin/env bash
# Synthetic Gradle wrapper stub for the mutation-check self-test fixture.
case "\$*" in
    "test")
        echo "3 tests completed"
        exit 0
        ;;
    "compileKotlin compileTestKotlin")
        if grep -rq "$MARKER" src; then
            echo "e: stub compile failure: $MARKER found under src/"
            exit 1
        fi
        exit 0
        ;;
    *)
        echo "gradlew stub: unsupported arguments: \$*"
        exit 99
        ;;
esac
EOF
chmod 755 "$ROOT/gradlew"

M1_PATTERN="$(grep -o '"M1|[^"]*' "$ROOT/scripts/mutation-check.sh" | cut -d'|' -f3)"
echo "M1 pattern: $M1_PATTERN"
echo "M1 matches in fixture verifier: $(grep -c "$M1_PATTERN" "$ROOT/$VERIFIER_PATH")"
(cd "$ROOT" && git init -q) || { echo "git init failed"; exit 1; }

chmod "$MODE" "$ROOT/scripts/mutation-check.sh"
echo "Fixture script mode: $(stat -c '%a %A' "$ROOT/scripts/mutation-check.sh")"
if [ -x "$ROOT/scripts/mutation-check.sh" ]; then echo "Fixture script executable: yes"; else echo "Fixture script executable: no"; fi

echo "Invoking: bash scripts/mutation-check.sh --selftest-compile-fault"
OUTPUT="$(cd "$ROOT" && bash scripts/mutation-check.sh --selftest-compile-fault 2>&1)"
STATUS=$?
echo "----- self-test output -----"
echo "$OUTPUT"
echo "----- end output (exit code $STATUS) -----"

FAILED=0
check() { # check <description> <0 if ok>
    if [ "$2" -eq 0 ]; then echo "ASSERT OK   : $1"; else echo "ASSERT FAIL : $1"; FAILED=1; fi
}
! grep -q "Permission denied" <<< "$OUTPUT"; check "no 'Permission denied'" $?
! grep -q "command not found" <<< "$OUTPUT"; check "no 'command not found'" $?
! grep -Eiq '(status|code)[^0-9]{0,4}12[67]([^0-9]|$)' <<< "$OUTPUT"; check "no 126/127 status" $?
grep -q "Baseline PASSED" <<< "$OUTPUT"; check "'Baseline PASSED' present" $?
grep -q "Injecting deliberate Kotlin compilation fault" <<< "$OUTPUT"; check "compile fault injection reached" $?
grep -q "failed to compile" <<< "$OUTPUT"; check "compile failure message present" $?
grep -Eiq 'Self-test PASSED.*(status|code)[^0-9]{0,4}2([^0-9]|$)' <<< "$OUTPUT"; check "child status exactly 2 confirmed" $?
[ "$STATUS" -eq 0 ]; check "self-test exit code 0" $?

if [ "$FAILED" -eq 0 ]; then echo "HARNESS RESULT: PASS"; else echo "HARNESS RESULT: FAIL"; fi
exit "$FAILED"
