package com.upspa.mobile.ffi.fakes

import uniffi.upspa_mobile_ffi.ClockPort
import uniffi.upspa_mobile_ffi.IdentityEvidence
import uniffi.upspa_mobile_ffi.IdentityEvidencePort
import uniffi.upspa_mobile_ffi.RedactedDiagnosticsPort
import uniffi.upspa_mobile_ffi.SecretBytes
import uniffi.upspa_mobile_ffi.SecureStoragePort
import uniffi.upspa_mobile_ffi.TransportPort
import java.util.concurrent.atomic.AtomicLong

/**
 * Fake adapters only.
 *
 * These implement the generated port interfaces without touching the network, the Android
 * Keystore, or BiometricPrompt. They exist so the host wiring compiles and the lifecycle can be
 * driven in a JVM unit test; the real adapters land in a later task.
 */

/** Clock that only advances when a test advances it. */
class FakeClock(startMillis: Long) : ClockPort {
    private val now = AtomicLong(startMillis)

    override fun nowEpochMillis(): ULong = now.get().toULong()

    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}

/** Records endpoints and returns a fixed-shape response. Never opens a socket. */
class FakeTransport : TransportPort {
    val calls = mutableListOf<String>()

    override fun send(endpoint: String, payload: ByteArray): ByteArray {
        calls += endpoint
        return ByteArray(minOf(payload.size, 32)) { 0xAA.toByte() }
    }
}

/** In-memory stand-in for the Android Keystore. Values stay as byte arrays, never Strings. */
class FakeSecureStorage : SecureStoragePort {
    private val entries = mutableMapOf<String, ByteArray>()

    override fun load(key: String): SecretBytes? =
        entries[key]?.let { SecretBytes(it.copyOf()) }

    override fun store(key: String, value: SecretBytes) {
        entries[key] = value.bytes.copyOf()
    }

    override fun remove(key: String) {
        entries.remove(key)
    }
}

/** Accepts evidence younger than a fixed window. No biometric prompt is shown. */
class FakeIdentity(private val freshnessWindowMillis: Long = 60_000L) : IdentityEvidencePort {
    override fun currentEvidence(): IdentityEvidence = IdentityEvidence(
        subjectTag = "fake-subject",
        authenticatedAtMillis = 0uL,
        attestation = byteArrayOf(1, 2, 3),
    )

    override fun isFresh(evidence: IdentityEvidence, nowEpochMillis: ULong): Boolean =
        (nowEpochMillis.toLong() - evidence.authenticatedAtMillis.toLong()) <= freshnessWindowMillis
}

/**
 * Collects only the stable codes the engine emits. There is no free-text parameter on this port,
 * so there is no path by which a credential could reach a log line.
 */
class RecordingDiagnostics : RedactedDiagnosticsPort {
    val records = mutableListOf<Triple<String, String, String>>()

    override fun record(eventCode: String, operation: String, detailCode: String) {
        records += Triple(eventCode, operation, detailCode)
    }

    fun codes(): List<String> = records.map { it.first }
}
