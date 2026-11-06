#!/usr/bin/env bash
# Fresh review follow-up proofs in a temporary git worktree (never the working branch).
# Each case applies exact-string mutations, runs the new test class and records the failing cases.
# Usage: bash win-mutations-fr.sh <repo> <worktree-dir> <commit> <mutate.py> <out-dir>
set -u
REPO="$1"; WT="$2"; COMMIT="$3"; MUTATE="$4"; OUTDIR="$5"
export PATH="/c/Program Files/Git/bin:$PATH"
POLICY=upspa-assetlink-verifier/src/main/kotlin/org/upspa/catalog/NormalizedPasswordPolicy.kt
ENTRY=upspa-assetlink-verifier/src/main/kotlin/org/upspa/catalog/CatalogEntry.kt
XDIR=upspa-assetlink-verifier/build/test-results/test
FILTER='org.upspa.catalog.FreshReviewRedactionRegressionTest'

POLICY_FIXED='override fun toString(): String = diagnosticSummary()'
POLICY_RAW='override fun toString(): String {\n        return "NormalizedPasswordPolicy(minLen=$minLen, maxLen=$maxLen, requireUpper=$requireUpper, " +\n                "requireLower=$requireLower, requireDigit=$requireDigit, requireSymbol=$requireSymbol, " +\n                "allowedSymbols='"'"'$allowedSymbols'"'"', forbidWhitespace=$forbidWhitespace, forbiddenSubstrings=$forbiddenSubstrings)"\n    }'
ENTRY_FIXED='policy=${policy.diagnosticSummary()}, aliasEvidence'
ENTRY_LINE_REVERT='policy=$policy, aliasEvidence'
ENTRY_RAW='policy=NormalizedPasswordPolicy(minLen=${policy.minLen}, maxLen=${policy.maxLen}, requireUpper=${policy.requireUpper}, requireLower=${policy.requireLower}, requireDigit=${policy.requireDigit}, requireSymbol=${policy.requireSymbol}, allowedSymbols='"'"'${policy.allowedSymbols}'"'"', forbidWhitespace=${policy.forbidWhitespace}, forbiddenSubstrings=${policy.forbiddenSubstrings}), aliasEvidence'

git -C "$REPO" worktree add -q --detach "$WT" "$COMMIT" || exit 90

run_case() { # run_case <id> <description> <file> <old> <new> [<file2> <old2> <new2>]
    local id="$1" desc="$2" out="$OUTDIR/mutation-$1.txt"
    shift 2
    git -C "$WT" checkout -q --force "$COMMIT"
    git -C "$WT" clean -qfdx -e upspa-assetlink-verifier/.gradle >/dev/null
    {
        echo "=== $id: $desc ==="
        echo "Environment: Windows, $("/c/Program Files/Git/bin/bash.exe" --version | head -1), temporary worktree based on $(git -C "$WT" rev-parse HEAD)"
        while [ "$#" -ge 3 ]; do
            echo "Mutation: replace exactly one occurrence in $1"
            python -I "$MUTATE" "$WT/$1" "$2" "$3" 2>&1 || echo "MUTATION NOT APPLIED"
            shift 3
        done
        echo "--- git status --short (worktree) ---"
        git -C "$WT" status --short
        echo "--- git diff HEAD (mutation, CR-at-EOL ignored) ---"
        git -C "$WT" --no-pager diff --ignore-cr-at-eol HEAD
        echo "\$ ./gradlew test --rerun --tests '$FILTER' --no-daemon"
    } > "$out" 2>&1
    (cd "$WT/upspa-assetlink-verifier" && ./gradlew test --rerun --tests "$FILTER" --no-daemon) >> "$out" 2>&1
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

run_case P "policy toString reverted to the raw rendering (entry keeps the summary)" \
    "$POLICY" "$POLICY_FIXED" "$POLICY_RAW"
run_case E "entry toString renders the raw policy fields itself (policy toString keeps the summary)" \
    "$ENTRY" "$ENTRY_FIXED" "$ENTRY_RAW"
run_case L "entry line reverted to policy=\$policy while the policy fix stays (expected equivalent mutant)" \
    "$ENTRY" "$ENTRY_FIXED" "$ENTRY_LINE_REVERT"
run_case B "both reverted line by line (the behavior at 9c99b60)" \
    "$POLICY" "$POLICY_FIXED" "$POLICY_RAW" "$ENTRY" "$ENTRY_FIXED" "$ENTRY_LINE_REVERT"

# Probe, not a mutation: does a hostname-shaped package name reach the rendered entry?
PROBE="$WT/upspa-assetlink-verifier/src/test/kotlin/org/upspa/catalog/ScratchPackageProbeTest.kt"
cat > "$PROBE" <<'KT'
package org.upspa.catalog

import org.junit.jupiter.api.Test

class ScratchPackageProbeTest {
    @Test
    fun probe() {
        val digest = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"
        val origin = "https://synthetic.example"
        val symbols = "!@#%"
        val json = """{"schemaVersion":1,"entries":[{"enrolledOrigin":"$origin","accountReference":"synthetic_user",
            "compatibilityProfileVersion":1,"encoderCounter":0,"policy":{"minLen":16,"maxLen":16,"requireUpper":true,
            "requireLower":true,"requireDigit":true,"requireSymbol":false,"allowedSymbols":"$symbols",
            "forbidWhitespace":true,"forbiddenSubstrings":[]},"aliasEvidence":{"status":"VERIFIED","reference":{
            "packageName":"synthetic.example","signingCertificateDigest":"$digest","sourceOrigin":"$origin"}}}]}"""
        val result = StrictCatalogValidator().validate(json)
        println("PROBE result: $result")
        if (result is CatalogValidationResult.Accepted) {
            val rendered = result.document.entries.single().toString()
            println("PROBE entry: $rendered")
            println("PROBE entry contains host: ${rendered.contains("synthetic.example")}")
        }
    }
}
KT
{
    echo "=== Probe: hostname-shaped package name, scratch test in the temporary worktree only ==="
    (cd "$WT/upspa-assetlink-verifier" && ./gradlew test --rerun --tests 'org.upspa.catalog.ScratchPackageProbeTest' -i --no-daemon) 2>&1 | grep -E 'PROBE|BUILD'
} > "$OUTDIR/probe-package-name.txt"

git -C "$REPO" worktree remove --force "$WT"
git -C "$REPO" worktree prune
git -C "$REPO" worktree list > "$OUTDIR/worktree-list-after-fr-proofs.txt"
