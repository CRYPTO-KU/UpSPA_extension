# UpSPA Mobile FFI Contract (v2)

Versioned host/engine boundary for the Android and future iOS clients. This replaces the
bootstrap-only surface that exported `bootstrap_info()` and nothing else.

## Design rules

1. **Versioned envelopes.** `MobileCommand`, `MobileEffect`, and `MobileEvent` each carry
   `contract_version`. The engine rejects a host outside `MIN_SUPPORTED_CONTRACT_VERSION ..=
   MOBILE_CONTRACT_VERSION` with `MobileError::UnsupportedContractVersion` rather than trying to
   interpret an unknown payload. Bump `MOBILE_CONTRACT_VERSION` on any wire-visible change; bump
   `MIN_SUPPORTED_CONTRACT_VERSION` only when dropping support for an older host.
2. **No secrets in strings.** Secret material crosses as `SecretBytes { bytes: Vec<u8> }`, which
   surfaces in Kotlin as `ByteArray`. This keeps values out of the JVM string pool, out of
   `toString()`, and zeroizable on drop in Rust. `SecretBytes` has a hand-written `Debug` that
   prints only a length.
3. **The engine performs no I/O.** Network, keystore, clock, and identity all arrive through ports.
   That is what makes the lifecycle deterministic under a fake clock.
4. **Typed errors only.** Every failure is a `MobileError` variant, generated into Kotlin as a
   `MobileException` subclass. There is no stringly-typed failure path.
5. **Redacted diagnostics.** `RedactedDiagnosticsPort::record` takes three code-shaped parameters
   and no free-text field, so there is no channel through which a credential can reach a log.
   Host-supplied failure reasons are filtered to `[A-Za-z0-9-_]` and truncated before they are
   stored in an event.

## Operation lifecycle

```
host                          engine
 |  submit(MobileCommand)  ->  | validate version, validate deadline, register operation
 |  <- MobileEffect            | (operation id assigned by the engine, opaque to the host)
 |  run effect via a port      |
 |  deliver(op, HostOutcome)-> | check known -> check settled -> check deadline -> check match
 |  <- MobileEvent             | operation marked settled; terminal
```

Cancellation (`cancel(op)`) is terminal and produces `EventBody::OperationCancelled`. A settled
operation — completed, cancelled, or expired — can never produce a second event.

### Ordering of checks in `deliver`

The order matters and is load-bearing for the negative test: *unknown* is checked before *settled*,
which is checked before *expired*, which is checked before the outcome is even inspected. A host
that reports `ProbeAck` on an expired operation therefore gets `OperationExpired`, not a success
event. Reordering these checks would break `stale_operation_cannot_be_reported_successful`.

## Ports

| Port | Kotlin interface | Responsibility |
|---|---|---|
| `TransportPort` | `TransportPort` | One request against a logical endpoint name (not a URL — routing is host policy) |
| `SecureStoragePort` | `SecureStoragePort` | Keystore-backed byte blobs |
| `ClockPort` | `ClockPort` | Wall clock in epoch millis |
| `IdentityEvidencePort` | `IdentityEvidencePort` | Proof the human was authenticated, plus freshness policy |
| `RedactedDiagnosticsPort` | `RedactedDiagnosticsPort` | Code-only diagnostics sink |

Only fake adapters exist at this stage, in `crates/upspa-mobile-ffi/src/fakes.rs` and
`apps/android/ffi/src/main/java/com/upspa/mobile/ffi/fakes/FakeAdapters.kt`. No real socket, no
real keystore, no biometric prompt.

## Binding generation

```bash
scripts/generate_mobile_bindings.sh kotlin
scripts/generate_mobile_bindings.sh swift   # placeholder path, no iOS host yet
```

The generator is the `uniffi-bindgen` binary built from `upspa-mobile-ffi` itself, so the generator
and the scaffolding are pinned to the same `uniffi` version by construction. Generation runs from
the compiled `cdylib` via `--library`, not from a hand-maintained `.udl`, so the bindings always
match the code that actually built. The output directory is deleted first, so a renamed symbol
cannot leave a stale file behind.

## Artifact layout

### Android (current)

```
apps/android/ffi/
  build.gradle.kts
  src/main/generated/uniffi/upspa_mobile_ffi/    # GENERATED — do not edit, do not hand-patch
  src/main/java/com/upspa/mobile/ffi/fakes/ # hand-written fake adapters
  src/main/jniLibs/
    arm64-v8a/libupspa_mobile_ffi.so        # aarch64-linux-android
    armeabi-v7a/libupspa_mobile_ffi.so      # armv7-linux-androideabi
    x86_64/libupspa_mobile_ffi.so           # x86_64-linux-android  (emulator)
  src/test/java/com/upspa/mobile/ffi/       # JVM tests against the desktop cdylib
```

`:app` depends on `:ffi`. The generated package is `uniffi.upspa_mobile_ffi`, derived from the
`[lib] name` in `Cargo.toml`; renaming the lib renames the package and breaks every import, so
treat it as part of the contract.

Rust targets are built with `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o
apps/android/ffi/src/main/jniLibs build --release -p upspa-mobile-ffi`. JNA supplies the loader,
which is why `:ffi` depends on the JNA **aar** rather than the plain jar.

### iOS (future, path reserved)

The layout follows the convention already established by `scripts/build-xcframework.sh` on
`intern/efe`, so the two do not have to be reconciled later:

```
build/xcframework/                          # BUILD OUTPUT — gitignored, never committed
  headers/module.modulemap
  headers/upspa_mobile_ffiFFI.h
  sim/libupspa_mobile_ffi.a                 # lipo of aarch64-apple-ios-sim + x86_64-apple-ios
  UpSPACore.xcframework/
mobile/iosApp/Packages/UpSPACore/Sources/UpSPACore/
  upspa_mobile_ffi.swift                    # generated Swift, committed with the package
```

The Swift side consumes the same `--library` generation path with `--language swift`; the header
and modulemap are folded into the XCFramework's `Headers/` directory. No iOS host exists yet, so
this section is a reserved layout, not a claim of implementation.

**Deliberate divergence from `build-xcframework.sh`.** That script writes Kotlin bindings to
`mobile/shared/build/generated/uniffi`. This contract writes them to
`apps/android/ffi/src/main/java` instead. A `build/` directory is wiped by `gradle clean` and is
gitignored, so bindings generated there cannot be reviewed in a diff — and the whole point of
committing generated bindings is that a reviewer can see the contract surface change. The Android
path also matches the `apps/android` module layout that actually exists on `mobile-dev`; the
`mobile/shared` path does not exist in this repository.

## Verification

```bash
cargo test -p upspa-mobile-ffi                       # lifecycle demo + negative tests
cargo build -p upspa-mobile-ffi --release            # cdylib for binding generation
scripts/generate_mobile_bindings.sh kotlin
cd apps/android && ./gradlew :ffi:testDebugUnitTest  # Kotlin mirror of the same assertions
```
