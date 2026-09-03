//! Fake adapters only. No real socket, no real keystore, no real biometric prompt.
//!
//! These are compiled into the library (not behind `cfg(test)`) so that Kotlin instrumentation and
//! the CLI demo can drive the same deterministic lifecycle the Rust tests do.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use crate::contract::{IdentityEvidence, MobileError, SecretBytes};
use crate::ports::*;

/// Clock that only moves when a test moves it.
#[derive(Debug)]
pub struct FakeClock {
    now: AtomicU64,
}

impl FakeClock {
    pub fn new(start_millis: u64) -> Self {
        Self {
            now: AtomicU64::new(start_millis),
        }
    }

    pub fn advance(&self, millis: u64) {
        self.now.fetch_add(millis, Ordering::SeqCst);
    }
}

impl ClockPort for FakeClock {
    fn now_epoch_millis(&self) -> u64 {
        self.now.load(Ordering::SeqCst)
    }
}

/// Echoes a fixed response. Never touches the network.
#[derive(Debug, Default)]
pub struct FakeTransport {
    pub calls: Mutex<Vec<String>>,
}

impl TransportPort for FakeTransport {
    fn send(&self, endpoint: String, payload: Vec<u8>) -> Result<Vec<u8>, MobileError> {
        self.calls.lock().expect("poisoned").push(endpoint);
        Ok(vec![0xAA; payload.len().min(32)])
    }
}

/// In-memory map standing in for the platform keystore.
#[derive(Debug, Default)]
pub struct FakeSecureStorage {
    entries: Mutex<HashMap<String, Vec<u8>>>,
}

impl SecureStoragePort for FakeSecureStorage {
    fn load(&self, key: String) -> Result<Option<SecretBytes>, MobileError> {
        Ok(self
            .entries
            .lock()
            .expect("poisoned")
            .get(&key)
            .cloned()
            .map(SecretBytes::new))
    }

    fn store(&self, key: String, value: SecretBytes) -> Result<(), MobileError> {
        self.entries
            .lock()
            .expect("poisoned")
            .insert(key, value.bytes.clone());
        Ok(())
    }

    fn remove(&self, key: String) -> Result<(), MobileError> {
        self.entries.lock().expect("poisoned").remove(&key);
        Ok(())
    }
}

/// Accepts evidence younger than a fixed window.
#[derive(Debug)]
pub struct FakeIdentity {
    pub freshness_window_millis: u64,
}

impl Default for FakeIdentity {
    fn default() -> Self {
        Self {
            freshness_window_millis: 60_000,
        }
    }
}

impl IdentityEvidencePort for FakeIdentity {
    fn current_evidence(&self) -> Result<IdentityEvidence, MobileError> {
        Ok(IdentityEvidence {
            subject_tag: "fake-subject".to_owned(),
            authenticated_at_millis: 0,
            attestation: vec![0x01, 0x02, 0x03],
        })
    }

    fn is_fresh(&self, evidence: IdentityEvidence, now_epoch_millis: u64) -> bool {
        now_epoch_millis.saturating_sub(evidence.authenticated_at_millis)
            <= self.freshness_window_millis
    }
}

/// Captures only the codes the engine emits, which is exactly what a redacted sink should see.
#[derive(Debug, Default)]
pub struct RecordingDiagnostics {
    pub records: Mutex<Vec<(String, String, String)>>,
}

impl RecordingDiagnostics {
    pub fn codes(&self) -> Vec<String> {
        self.records
            .lock()
            .expect("poisoned")
            .iter()
            .map(|(code, _, _)| code.clone())
            .collect()
    }
}

impl RedactedDiagnosticsPort for RecordingDiagnostics {
    fn record(&self, event_code: String, operation: String, detail_code: String) {
        self.records
            .lock()
            .expect("poisoned")
            .push((event_code, operation, detail_code));
    }
}
