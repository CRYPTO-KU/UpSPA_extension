#!/usr/bin/env bash
# Builds UpSPACore.xcframework from crates/upspa-ffi and generates the Swift
# bindings. Run from CI, never from an Xcode run-script phase: driving cargo
# from Xcode makes CI failures unreadable and couples iOS build time to the
# Rust toolchain.
#
# Requires: macOS, Xcode CLT, rustup targets below.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/xcframework"
CRATE="upspa-ffi"
LIB="libupspa_ffi.a"

DEVICE="aarch64-apple-ios"
SIM_ARM="aarch64-apple-ios-sim"
SIM_X86="x86_64-apple-ios"

rustup target add "$DEVICE" "$SIM_ARM" "$SIM_X86"

rm -rf "$OUT"
mkdir -p "$OUT/sim" "$OUT/headers"

for T in "$DEVICE" "$SIM_ARM" "$SIM_X86"; do
  cargo build --release -p "$CRATE" --target "$T"
done

# Both simulator slices must live in one fat library; an xcframework cannot
# carry two slices for the same platform+variant separately.
lipo -create \
  "$ROOT/target/$SIM_ARM/release/$LIB" \
  "$ROOT/target/$SIM_X86/release/$LIB" \
  -output "$OUT/sim/$LIB"

# One bindgen invocation is the point of UniFFI. Swift and Kotlin bindings are
# generated from the same built library so they cannot drift apart.
cargo run --release --bin uniffi-bindgen -- generate \
  --library "$ROOT/target/$DEVICE/release/$LIB" \
  --language swift \
  --out-dir "$OUT/swift"

cargo run --release --bin uniffi-bindgen -- generate \
  --library "$ROOT/target/$DEVICE/release/$LIB" \
  --language kotlin \
  --out-dir "$ROOT/mobile/shared/build/generated/uniffi"

# UniFFI emits a modulemap under a name it chooses; normalise it so the Swift
# package's header path is stable across uniffi versions.
mv "$OUT/swift/"*.h "$OUT/headers/"
mv "$OUT/swift/"*.modulemap "$OUT/headers/module.modulemap"

xcodebuild -create-xcframework \
  -library "$ROOT/target/$DEVICE/release/$LIB" -headers "$OUT/headers" \
  -library "$OUT/sim/$LIB" -headers "$OUT/headers" \
  -output "$OUT/UpSPACore.xcframework"

# The generated Swift source is part of the package, not the binary target.
cp "$OUT/swift/"*.swift "$ROOT/mobile/iosApp/Packages/UpSPACore/Sources/UpSPACore/"

echo "built $OUT/UpSPACore.xcframework"
