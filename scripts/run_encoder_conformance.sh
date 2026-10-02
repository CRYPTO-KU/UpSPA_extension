#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "============================================================"
echo "  UpSPA Password Encoder Conformance & Qualification Suite   "
echo "============================================================"

echo "--- Toolchain Environment ---"
echo "Rustc:   $(rustc --version 2>/dev/null || echo 'not found')"
echo "Cargo:   $(cargo --version 2>/dev/null || echo 'not found')"
echo "Node:    $(node --version 2>/dev/null || echo 'not found')"
echo "NPM:     $(npm --version 2>/dev/null || echo 'not found')"
echo "Python3: $(python3 --version 2>/dev/null || echo 'not found')"
echo ""

echo "Step 1: Running Rust corpus consumer..."
cargo test --locked -p upspa-core --test vectors_password_encoder

echo ""
echo "Step 2: Running Rust encoder property and boundary tests..."
cargo test --locked -p upspa-core --test encoder_properties

echo ""
echo "Step 3: Running TypeScript corpus consumer and encoder tests..."
npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts

echo ""
echo "Verifying shared normalization controls and the excluded gap..."
node --import tsx scripts/verify_encoder_normalization.mjs

echo ""
echo "Step 4: Deterministic corpus regeneration check..."
./node_modules/.bin/tsx scripts/gen_password_vectors.mjs
profile_changes=$(git status --porcelain --untracked-files=all -- test-vectors/compatibility-profile-v1/) && test -z "$profile_changes"

echo ""
echo "Step 5: Verifying temporary corrupted vector detection..."
./scripts/verify_corpus_corruption.sh

echo ""
echo "Step 6: Running qualification regression controls..."
python3 scripts/test_encoder_conformance.py

echo "Step 7: Running security gates and fixture isolation tests..."
python3 tools/security-gates/run_gates.py .
python3 tools/security-gates/test_negative_fixtures.py
python3 tools/security-gates/test_positive_fixtures.py

echo ""
echo "Step 8: Checking Rust formatting..."
cargo fmt --all -- --check

echo "============================================================"
echo "  All Conformance, Parity, and Security Checks PASSED!       "
echo "============================================================"
