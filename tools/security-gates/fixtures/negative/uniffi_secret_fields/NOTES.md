# Fixture: uniffi_secret_fields

Six intended failures across every UniFFI surface shape this gate scans:

1. `master_password`: secret-shaped struct field, bare `String`.
2. `state_key`: same as #1 , but with "key" after an underscore.
3. `SpacedType.master_secret: Option < String >`: syntactically
   equivalent to `Option<String>` with internal whitespace.
4. `UnlockEvent::Unlocked { master_secret }`: an enum variant with a
   named field.
5. `Session::unlock`: inside a whole-impl-block `#[uniffi::export] impl
   Session { ... }`.
6. `UnlockCallback::on_unlock`, inside a `#[uniffi::export(with_foreign)]`
   trait. Same shape gap as #5, different UniFFI construct.

`contract_version`, `request_id`, `ping`, `status_report_for_diagnostics_only`,
`Locked`, and `on_ready` are present throughout to prove the gate
doesn't flag ordinary, non-secret-shaped fields or methods; if it did,
that would be a false-positive bug, not a real finding.

**Companion case this fixture deliberately does NOT cover:** the same
five shapes, correctly typed as byte buffers, must NOT fail;
that's `../../positive/uniffi_secret_fields/`.
