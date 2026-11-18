package com.upspa.mobile.ffi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.upspa_mobile_ffi.NormalizedPasswordPolicy
import uniffi.upspa_mobile_ffi.SecretBytes
import uniffi.upspa_mobile_ffi.encodePassword

/**
 * Accepted-encoder control on a device: one synthetic input through the packaged x86_64 library
 * and the generated bindings. Assertions carry no secret or output values.
 */
class AndroidEncoderSmokeTest {
    private val symbols = "!@#"
    private val policy = NormalizedPasswordPolicy(
        minLen = 12u, maxLen = 20u,
        requireUpper = true, requireLower = true, requireDigit = true, requireSymbol = true,
        allowedSymbols = symbols, forbidWhitespace = true, forbiddenSubstrings = emptyList(),
    )

    private fun encode(): ByteArray =
        encodePassword(SecretBytes("synthetic-device-smoke".toByteArray()), policy, "synthetic-account", 1u).component1()

    @Test
    fun encoderAcceptsValidPolicyOnDevice() {
        val out = String(encode(), Charsets.UTF_8)
        assertTrue("length outside policy bounds", out.length in 12..20)
        assertTrue("missing upper", out.any { it.isUpperCase() })
        assertTrue("missing lower", out.any { it.isLowerCase() })
        assertTrue("missing digit", out.any { it.isDigit() })
        assertTrue("missing allowed symbol", out.any { it in symbols })
        assertTrue("whitespace present", out.none { it.isWhitespace() })
    }

    @Test
    fun encoderIsDeterministicOnDevice() {
        assertArrayEquals("same input gave different output", encode(), encode())
    }
}