# Mobile Compatibility Profile v1

This directory belongs to the canonical `UpSPA_extension` repository.

The production vector corpus must be generated from:

- `packages/extension/src/shared/passwordPolicy.ts`
- encoder identifier `upspa-password-encoding-v2`
- the reviewed repository commit recorded in `manifest.json`

The User Study repository's HKDF vectors are research evidence only and must not be copied here as
production compatibility fixtures because that repository implements a different encoder.

The corpus is intentionally pending during the walking-skeleton bootstrap. BASE-03 will add a
TypeScript generator and at least 20 synthetic cases before real mobile authentication uses the
Rust encoder.
