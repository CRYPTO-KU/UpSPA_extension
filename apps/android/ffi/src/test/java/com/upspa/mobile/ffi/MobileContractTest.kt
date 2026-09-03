package com.upspa.mobile.ffi

import com.upspa.mobile.ffi.fakes.FakeClock
import com.upspa.mobile.ffi.fakes.FakeIdentity
import com.upspa.mobile.ffi.fakes.FakeSecureStorage
import com.upspa.mobile.ffi.fakes.FakeTransport
import com.upspa.mobile.ffi.fakes.RecordingDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import uniffi.upspa_mobile_ffi.CommandBody
import uniffi.upspa_mobile_ffi.Deadline
import uniffi.upspa_mobile_ffi.EffectBody
import uniffi.upspa_mobile_ffi.EventBody
import uniffi.upspa_mobile_ffi.HostOutcome
import uniffi.upspa_mobile_ffi.MobileCommand
import uniffi.upspa_mobile_ffi.MobileEngine
import uniffi.upspa_mobile_ffi.MobileException
import uniffi.upspa_mobile_ffi.OperationId
import uniffi.upspa_mobile_ffi.mobileContractVersion

/**
 * Host-side mirror of `crates/upspa-mobile-ffi/tests/lifecycle.rs`.
 *
 * The point of duplicating the assertions in Kotlin is to prove the *generated* bindings expose the
 * typed errors as real exception subclasses, rather than collapsing them into a generic failure.
 */
class MobileContractTest {

    private val t0 = 1_700_000_000_000L

    private fun probe(deadlineMillis: Long) = MobileCommand(
        contractVersion = mobileContractVersion(),
        requestTag = "probe",
        deadline = Deadline(deadlineMillis.toULong()),
        body = CommandBody.Probe(echoTag = "lifecycle-demo"),
    )

    @Test
    fun `deterministic probe lifecycle`() {
        val clock = FakeClock(t0)
        val diagnostics = RecordingDiagnostics()
        val engine = MobileEngine(
            FakeTransport(),
            FakeSecureStorage(),
            clock,
            FakeIdentity(),
            diagnostics,
        )

        val effect = engine.submit(probe(t0 + 5_000))
        assertEquals("op-000001-probe", effect.operation.value)
        assertTrue(effect.body is EffectBody.AckImmediately)

        clock.advance(250)
        val event = engine.deliver(
            effect.operation,
            HostOutcome.ProbeAck(echoTag = "lifecycle-demo"),
        )

        assertEquals(effect.operation, event.operation)
        assertEquals(1u, event.sequence)
        assertTrue(event.body is EventBody.ProbeCompleted)
        assertEquals(0u, engine.openOperationCount())
        assertEquals(
            listOf("operation.started", "operation.settled"),
            diagnostics.codes(),
        )
    }

    /** A stale operation must not be reportable as a success from the host side either. */
    @Test
    fun `stale operation cannot be reported successful`() {
        val clock = FakeClock(t0)
        val engine = MobileEngine(
            FakeTransport(),
            FakeSecureStorage(),
            clock,
            FakeIdentity(),
            RecordingDiagnostics(),
        )

        val effect = engine.submit(probe(t0 + 1_000))
        clock.advance(1_001)

        try {
            engine.deliver(effect.operation, HostOutcome.ProbeAck(echoTag = "lifecycle-demo"))
            fail("expected MobileException.OperationExpired")
        } catch (expected: MobileException.OperationExpired) {
            assertEquals(effect.operation.value, expected.operation)
        }
    }

    @Test
    fun `unknown operation cannot be reported successful`() {
        val engine = MobileEngine(
            FakeTransport(),
            FakeSecureStorage(),
            FakeClock(t0),
            FakeIdentity(),
            RecordingDiagnostics(),
        )

        try {
            engine.deliver(
                OperationId("op-999999-forged"),
                HostOutcome.RequestSucceeded(response = byteArrayOf(1, 2, 3)),
            )
            fail("expected MobileException.UnknownOperation")
        } catch (expected: MobileException.UnknownOperation) {
            assertEquals("op-999999-forged", expected.operation)
        }
    }
}
