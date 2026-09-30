# Mobile Compatibility Profile v1

This directory belongs to the canonical `UpSPA_extension` repository.

The compatibility corpus is generated from:

- `packages/extension/src/shared/passwordPolicy.ts`
- encoder identifier `upspa-password-encoding-v2`
- the reviewed repository commit recorded in `manifest.json`

The User Study repository's HKDF vectors are research evidence only and must not be copied here as
production compatibility fixtures because that repository implements a different encoder.

The committed corpus contains 22 synthetic cases: 21 accepted vectors and one deliberately
mismatched expected value (`v022`). TypeScript and Rust comparison tests must match every accepted
case and detect the mismatch in `v022`. This is a comparison control, not an invalid-policy case;
corrupting an accepted vector must make the suite fail.

Rust parity currently requires fully normalized policies. Partial/raw policy normalization is a
separate compatibility decision. The profile's `stable` label does not certify native bindings,
complete toolchain/wire pins, or production readiness.

After running `npm ci` from the root with development dependencies enabled, run:

```bash
cargo test --locked -p upspa-core --test vectors_password_encoder
npm -w upspa-extension test -- src/shared/passwordPolicy.test.ts
./node_modules/.bin/tsx scripts/gen_password_vectors.mjs
git diff --exit-code -- test-vectors/compatibility-profile-v1/
```

Generation writes `vectors.json`; use a disposable checkout if preserving local changes. The diff
check assumes a clean, committed corpus directory; it also detects unrelated edits in that directory.
Keep the manifest's encoder revision tied to the actual source used. Do not update expected values
or provenance merely to hide a consumer mismatch. Failure output should contain vector IDs and
generic reasons, not derived values.

See the [mobile development plan](../../docs/mobile-development-plan.md) for
Kotlin/Swift conformance and profile qualification work.
