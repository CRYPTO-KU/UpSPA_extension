# Encoder Compatibility Gaps

This document lists the gaps found between the Rust and TypeScript encoder implementations.

## Partial Policy Support

TypeScript accepts partial policy objects and fills missing fields with default values. Rust currently requires a complete policy struct where all fields are already set. Passing partial JSON to Rust causes a decoding error.

## Inverted Length Ranges

When minimum length is larger than maximum length, Rust increases maximum length to match minimum length. TypeScript calculates bounds through different branches. For complete positive integer policies both promote maximum to minimum. This observed behavior is retained; raw/partial/coerced input equivalence remains outside qualification.

## Counter Types

Rust uses an unsigned 32-bit integer for the counter. TypeScript uses the JavaScript number type. TypeScript does not reject negative numbers or decimal values before building the seed string.

## Error Types

Rust returns specific error types using an enum. TypeScript throws a standard Error object with text messages. Callers in TypeScript must check error strings rather than error codes.

## Mobile Bindings

The Rust core encoder is not yet exported in the mobile FFI library. Android and iOS applications cannot call the encoder directly through UniFFI yet.

## Character Sets

Rust checks ASCII character ranges using byte methods. TypeScript checks characters using regular expressions. Behavior for non-ASCII characters outside standard ASCII is currently unspecified.

## Attempt Limit

Both implementations stop after 128 attempts if a candidate does not match the policy. This limit is hardcoded in both codebases.

## Length cap and resource limits

The supported policy boundary is complete normalized ASCII policies with
`8 <= minLen <= maxLen <= 64`. Neither API enforces a hard maximum of 64:
`minLen=65,maxLen=65` produces 65 characters in both encoders. Capping requested
maximum happens before promoting it to minimum. Large minima have no explicit
resource bound. Adding rejection or a hard cap would change compatibility and
requires a separate decision; this change only documents and tests the limitation.

## Toolchain qualification

Rust 1.81.0 was advertised without effective selection. The root `stable` file
could override the installed version, and locked `base64ct` 1.8.3 declares Edition
2024 and Rust 1.85. Explicit Rust 1.81 is incompatible with that dependency.
The dedicated workflow now overrides selection with Rust 1.95.0 and checks the
version; Node remains 24.19.0. The follow-up ran the suite locally with Rust 1.95.0 / Node 24.19.0 after a
locked npm install. Actual Rust 1.81 also rejected locked `cpufeatures` 0.3.0
as Edition 2024. GitHub Actions execution and its Python 3.12 runtime, plus
native Kotlin/Swift consumers, remain separate evidence.

## Failure-output coverage

The corruption helper now rejects full synthetic expected values, original
candidates and secret inputs anywhere in captured consumer output. Logging-fault
controls cover both consumers and unexpected-result paths. Partial, transformed,
or independently changed candidate values are not exhaustively detected; the
helper does not establish a universal absence-of-leakage guarantee.

## Existing dependency installation warnings

The locked npm installation succeeds but is not warning-free. `npm audit --json`
reported 9 existing findings: 3 moderate, 5 high and 1 critical. Affected packages
are `@vitest/mocker`, `esbuild`, `nanoid`, `postcss`, `rollup`, `undici`, `vite`,
`vite-node` and `vitest`. Suggested fixes include major Vite/Vitest upgrades;
blind `npm audit fix --force` is not a conformance repair.

`npm ci` also reports deprecated `whatwg-encoding` 3.1.1, `q` 1.5.1 and
`sourcemap-codec` 1.4.8, plus npm 11.17 install-script approval notices for the
locked esbuild versions. The encoder checks succeed with the locked installation.
These dependency/tooling warnings were not suppressed or resolved by changing
shared manifests or lockfiles, which the task requires preserving. Dependency
modernization and extension build/runtime verification belong in a separate
change if that constraint is relaxed.
