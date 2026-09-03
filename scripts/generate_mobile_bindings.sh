#!/usr/bin/env bash
# Reproducible UniFFI binding generation for the UpSPA mobile boundary.
#
# Usage:
#   scripts/generate_mobile_bindings.sh            # Kotlin bindings (default)
#   scripts/generate_mobile_bindings.sh kotlin
#   scripts/generate_mobile_bindings.sh swift      # future iOS host
#
# Determinism notes:
#   - The generator is the `uniffi-bindgen` binary inside `upspa-mobile-ffi` itself, so the
#     generator version can never drift from the scaffolding version.
#   - Generation runs off the compiled cdylib (`--library`), not a hand-maintained .udl file,
#     so the bindings are always derived from the code that actually built.
#   - The output directory is wiped before generation; no stale symbols can survive a rename.

set -euo pipefail

LANGUAGE="${1:-kotlin}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="upspa-mobile-ffi"
PROFILE="release"
TARGET_DIR="${CARGO_TARGET_DIR:-$REPO_ROOT/target}"

case "$(uname -s)" in
  Darwin) LIB_NAME="libupspa_mobile_ffi.dylib" ;;
  MINGW*|MSYS*|CYGWIN*) LIB_NAME="upspa_mobile_ffi.dll" ;;
  *) LIB_NAME="libupspa_mobile_ffi.so" ;;
esac

case "$LANGUAGE" in
  kotlin) OUT_DIR="$REPO_ROOT/apps/android/ffi/src/main/java" ;;
  swift)  OUT_DIR="$REPO_ROOT/build/xcframework/swift" ;;
  *) echo "unsupported language: $LANGUAGE (expected kotlin or swift)" >&2; exit 2 ;;
esac

echo "==> building $CRATE ($PROFILE)"
cargo build --profile "$PROFILE" -p "$CRATE"

LIB_PATH="$TARGET_DIR/$PROFILE/$LIB_NAME"
if [[ ! -f "$LIB_PATH" ]]; then
  echo "expected library not found at $LIB_PATH" >&2
  exit 1
fi

echo "==> clearing $OUT_DIR"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

echo "==> generating $LANGUAGE bindings from $LIB_PATH"
cargo run --profile "$PROFILE" -p "$CRATE" --bin uniffi-bindgen -- \
  generate --library "$LIB_PATH" \
  --language "$LANGUAGE" \
  --out-dir "$OUT_DIR" \
  --no-format

echo "==> generated:"
find "$OUT_DIR" -type f | sort
