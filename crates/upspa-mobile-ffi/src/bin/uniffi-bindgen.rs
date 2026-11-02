//! Reproducible binding generator. Pinned to the same uniffi version as the library, so bindings
//! can never drift from the scaffolding they are generated against.
fn main() {
    uniffi::uniffi_bindgen_main()
}
