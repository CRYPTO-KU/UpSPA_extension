#!/usr/bin/env bash
# Prints per-class and total test counts from Gradle JUnit XML reports.
# Usage: bash xml-totals.sh <test-results-dir>
set -u
awk '
/<testsuite / {
    n = $0; sub(/.*<testsuite name="/, "", n); sub(/".*/, "", n)
    t = $0; sub(/.* tests="/, "", t); sub(/".*/, "", t)
    s = $0; sub(/.*skipped="/, "", s); sub(/".*/, "", s)
    f = $0; sub(/.*failures="/, "", f); sub(/".*/, "", f)
    e = $0; sub(/.*errors="/, "", e); sub(/".*/, "", e)
    printf "%s tests=%s skipped=%s failures=%s errors=%s\n", n, t, s, f, e
    T += t; S += s; F += f; E += e
}
END { printf "TOTAL tests=%d passed=%d failed=%d errors=%d skipped=%d\n", T, T - S - F - E, F, E, S }
' "$1"/TEST-*.xml
