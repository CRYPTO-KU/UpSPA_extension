// Seeded-negative fixture for gates/uniffi_secret_fields.py
//
// Intended failures, one per scanned UniFFI surface shape:
//
//   1. `BadUnlockRequest.master_password`: secret-shaped struct field,
//      bare `String`, the baseline case.
//   2. `BadUnlockRequest.state_key`: same problem, but with the
//      secret-shaped word ("key") appearing after an underscore rather
//      than as a whole standalone word.
//   3. `UnlockEvent::Unlocked { master_secret }`: an enum variant with
//      a named (struct-like) field. Enum variant fields have no `pub`
//      keyword, unlike struct fields.
//   4. `Session::unlock`, a method inside a whole-impl-block
//      `#[uniffi::export] impl Session { ... }`.
//   5. `UnlockCallback::on_unlock`: a method inside a
//      `#[uniffi::export(with_foreign)]` trait. Same shape gap as #4,
//      different UniFFI construct.
//   6. `SpacedType.master_secret: Option < String >`: syntactically
//      identical to `Option<String>` but with internal whitespace.
//
// `contract_version`, `request_id`, `ping`, `on_ready`, and the
// `Locked`/diagnostics cases are present throughout to prove the gate
// doesn't flag ordinary, non-secret-shaped fields or methods;
// if it did, that would be a false-positive bug, not a real finding.
//
// See ../../positive/uniffi_secret_fields/ for the companion cases this
// fixture does NOT cover: the same five shapes, but correctly typed as
// byte buffers, which must NOT fail.

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BadUnlockRequest {
    pub contract_version: u32,
    pub request_id: String,
    pub master_password: String,
    pub state_key: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SpacedType {
    pub master_secret: Option < String >,
}

#[uniffi::export]
pub fn unlock_with_password(master_password: String, request_id: String) -> bool {
    let _ = (master_password, request_id);
    false
}

#[derive(uniffi::Enum)]
pub enum UnlockEvent {
    Unlocked { master_secret: String, contract_version: u32 },
    Locked,
}

pub struct Session;

#[uniffi::export]
impl Session {
    pub fn ping(&self) -> bool {
        true
    }

    pub fn status_report_for_diagnostics_only(&self, note: String) -> String {
        note
    }

    pub fn unlock(&self, master_secret: String) -> bool {
        false
    }
}

#[uniffi::export(with_foreign)]
pub trait UnlockCallback: Send + Sync {
    fn on_unlock(&self, master_secret: String);
    fn on_ready(&self);
}
