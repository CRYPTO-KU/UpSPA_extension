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
   surfaces in Kotlin as `ByteArray`. This keeps values out of the JVM string pool and out of
   `toString()`. `SecretBytes` has a hand-written `Debug` that prints only a length. Because a
   UniFFI record cannot implement `Drop`, the engine moves every incoming secret into an internal
   `SecretGuard` (which zeroizes in `Drop`) as its very first action in `submit`, `deliver`, and
   `encode_password`. Erasure therefore happens on success and on every early return, by
   construction, not by a per-path `zeroize()` call.
3. **The engine performs no I/O.** Network, keystore, clock, and identity all arrive through ports.
   That is what makes the lifecycle deterministic under a fake clock.
4. **Typed errors only.** Every failure is a `MobileError` variant, generated into Kotlin as a
   `MobileException` subclass. There is no stringly-typed failure path.
5. **Redacted diagnostics.** `RedactedDiagnosticsPort::record` takes three code-shaped parameters
   and no free-text field, so there is no channel through which a credential can reach a log.
   Host-supplied failure reasons are filtered to `[A-Za-z0-9-_]` and truncated before they are
   stored in an event. `MobileCommand::request_tag` is host-controlled text and is discarded on
   entry: it never appears in an operation ID, a diagnostics record, or an error message.

## Operation lifecycle

```
host                          engine
 |  submit(MobileCommand)  ->  | guard secrets, validate version, validate deadline, register
 |  <- MobileEffect            | (operation id `op-NNNNNN` minted by the engine, opaque to host)
 |  run effect via a port      |
 |  deliver(op, HostOutcome)-> | check known -> check settled -> check deadline -> check match
 |  <- MobileEvent             | operation marked settled; terminal
```

Cancellation (`cancel(op)`) is terminal and produces `EventBody::OperationCancelled`, unless the
deadline has already passed: `cancel` applies the same expiry-before-outcome rule as `deliver`,
settles the operation as expired, and returns `MobileError::OperationExpired`. A settled
operation — completed, cancelled, or expired — can never produce a second event.

A command that arrives already expired is rejected with `OperationExpired` carrying a freshly
minted, never-registered engine ID, so the rejection can be correlated in diagnostics without
echoing any host text.

### Ordering of checks in `deliver`

The order matters and is load-bearing for the negative test: *unknown* is checked before *settled*,
which is checked before *expired*, which is checked before the outcome is even inspected. A host
that reports `ProbeAck` on an expired operation therefore gets `OperationExpired`, not a success
event. Reordering these checks would break `stale_operation_cannot_be_reported_successful`.

## Ports

| Port | Kotlin interface | Responsibility |
|---|---|---|
| `TransportPort` | `TransportPort` | One request against a logical endpoint name (not a URL — routing is host policy) |
| `SecureStoragePort` | `SecureStoragePort` | Keystore-backed byte blobs, addressed by a non-secret `slot` name |
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

### Binding and API drift check

```bash
python3 scripts/check_mobile_bindings.py              # fails on drift or unexpected exports
python3 scripts/check_mobile_bindings.py --self-test  # proves seeded drift makes it fail
```

The check regenerates the bindings into a temporary directory with the crate's own
`uniffi-bindgen` (UniFFI 0.28, pinned by `Cargo.lock`, run with `--locked`) and compares them
byte-for-byte with the committed tree. It then extracts every exported callable from the
committed Kotlin and compares it with `scripts/mobile_api_allowlist.txt`. The allowlist is
deliberately an allowlist: a newly leaked helper fails even if nobody named it in advance.
Internal engine helpers live in a non-exported `impl` block and must never appear there.

## Password encoder facade

`encode_password(secret: SecretBytes, policy: NormalizedPasswordPolicy, account_id, counter)`
delegates to `upspa_core::password_encoder::encode_secret_as_password`. It reimplements nothing.
It accepts only policies for which `upspa_core::password_encoder::is_normalized_policy` holds,
because the Rust/TypeScript parity contract covers normalized policies only, and returns the
password as `SecretBytes` so the host holds a wipeable `ByteArray`. Failures are
`MobileError::PasswordEncoding` with fixed reason codes (`policy-not-normalized`,
`secret-not-utf8`, `impossible-policy`, `empty-pool`, `exhausted-attempts`, `invalid-policy`).

Known limit: the core encoder builds intermediate seed strings internally that it does not
zeroize. That is outside this facade.

Kotlin conformance against the shared 22-vector corpus runs through these bindings:

```bash
cd apps/android
./gradlew :ffi:testDebugUnitTest --tests 'com.upspa.mobile.ffi.EncoderConformanceTest'
cd ../.. && scripts/demo_corrupted_vector.sh   # corrupted temp copy must fail the run
```

The corpus is read-only. Output contains vector IDs only.

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

JVM unit tests use the host build instead: the `:ffi` Gradle module's `cargoBuildHost` task runs
`cargo build --locked --release -p upspa-mobile-ffi` and every test task depends on it and sets
`jna.library.path` to the resulting `target/release` directory (respecting `CARGO_TARGET_DIR`).
`cargo` must be on `PATH`.

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
`apps/android/ffi/src/main/generated` instead. A `build/` directory is wiped by `gradle clean` and is
gitignored, so bindings generated there cannot be reviewed in a diff — and the whole point of
committing generated bindings is that a reviewer can see the contract surface change. The Android
path also matches the `apps/android` module layout that actually exists on `mobile-dev`; the
`mobile/shared` path does not exist in this repository.

## Verification

```bash
cargo test --locked -p upspa-core -p upspa-mobile-ffi   # lifecycle, negatives, erasure, vectors
python3 scripts/check_mobile_bindings.py                # binding drift + exported API
python3 scripts/check_mobile_bindings.py --self-test    # seeded drift must fail
cd apps/android && ./gradlew :ffi:testDebugUnitTest :app:assembleDebug  # builds host cdylib first
cd ../.. && scripts/demo_corrupted_vector.sh            # corrupted vector must fail
python3 tools/security-gates/run_gates.py .             # mobile security gates
```

CI runs all of the above in `.github/workflows/mobile-ffi.yml`.
