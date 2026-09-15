# Fixture: uniffi_secret_fields (positive)

The same five UniFFI surface shapes as the negative fixture.
Struct field, enum variant field, standalone export fn, export-impl method,
export(with_foreign) trait method; every secret-shaped name typed as `Vec<u8>`/`Option<Vec<u8>>`.
Must produce zero findings from `uniffi_secret_fields`.

Two things this proves together, not separately:

1. Byte buffers are correctly accepted as-is;
2. Acceptance holds across every shape the gate now scans,
   not just the two shapes an earlier version was limited to;
   a gate that only checked structs/standalone-fns for correctness
   would have nothing to say about whether the other three shapes
   are byte-buffer-clean too, since it couldn't see them at all
   before the shape-coverage fix.

Run via `test_positive_fixtures.py`, the companion to `test_negative_fixtures.py`.
That script proves a fixture fails correctly; this one proves a fixture that *should* be clean actually is.