#!/usr/bin/env bash
# Cross-binding vector runner. One vector set, three binding layers.
#
# There are three bindings over one Rust core: wasm for the browser extension
# for the browser extension, Swift for iOS, Kotlin for Android. If they disagree, the
# browser and the phone derive different credentials for the same account, which
# is a vault-level defect rather than a test failure. So this gates merge.
#
# NOT YET TRUSTED: no divergence has ever been injected between the three
# bindings, so this check has never been seen to fail. First run should perturb
# one binding deliberately and confirm the CI diff catches it.
#
# Usage: ./scripts/vectors.sh {wasm|swift|kotlin}
# Output: out/vectors-$1.json, compared byte-for-byte by CI.
set -euo pipefail

TARGET="${1:?usage: vectors.sh {wasm|swift|kotlin}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"; mkdir -p "$OUT"

# Fixed inputs. No randomness: any operation needing entropy is excluded from
# this suite, because a vector that differs on every run cannot be compared.
# Entropy-dependent paths are covered by the Rust unit tests instead.
VEC_UID="upspa-cross-binding-vector"
VEC_LSJ="example.com"
VEC_PASSWORD="correct horse battery staple"

case "$TARGET" in
  wasm)
    node "$ROOT/scripts/vectors/run_wasm.mjs" \
      --uid "$VEC_UID" --lsj "$VEC_LSJ" --password "$VEC_PASSWORD" \
      > "$OUT/vectors-wasm.json"
    ;;
  swift)
    swift run --package-path "$ROOT/mobile/iosApp/Packages/UpSPACore" vectors \
      --uid "$VEC_UID" --lsj "$VEC_LSJ" --password "$VEC_PASSWORD" \
      > "$OUT/vectors-swift.json"
    ;;
  kotlin)
    (cd "$ROOT/mobile" && ./gradlew --quiet :shared:runVectors \
      -Puid="$VEC_UID" -Plsj="$VEC_LSJ" -Ppassword="$VEC_PASSWORD") \
      > "$OUT/vectors-kotlin.json"
    ;;
  *) echo "unknown target: $TARGET" >&2; exit 2 ;;
esac

echo "wrote $OUT/vectors-$TARGET.json" >&2
