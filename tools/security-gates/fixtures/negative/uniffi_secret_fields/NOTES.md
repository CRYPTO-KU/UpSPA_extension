# Fixture: uniffi_secret_fields

A secret-related field name is declared as a plain string type in both
a UniFFI record and an exported function parameter. This is exactly
"represent secret values as byte buffers or explicit secret types, not
ordinary strings" is intended to avoid. Two clearly non-secret fields
are also included to show that ordinary fields are not flagged.
