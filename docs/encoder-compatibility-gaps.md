# Encoder Compatibility Gaps

This document lists the gaps found between the Rust and TypeScript encoder implementations.

## Partial Policy Support

TypeScript accepts partial policy objects and fills missing fields with default values. Rust currently requires a complete policy struct where all fields are already set. Passing partial JSON to Rust causes a decoding error.

## Inverted Length Ranges

When minimum length is larger than maximum length, Rust increases maximum length to match minimum length. TypeScript calculates bounds through different branches. Both implementations give deterministic results, but the specification does not explicitly define this edge case.

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
