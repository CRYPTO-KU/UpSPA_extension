package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewRound2RegressionTest {

    private val validator = StrictCatalogValidator()

    // -------------------------------------------------------------
    // Finding 1: Accepted policies break browser/Rust parity
    // -------------------------------------------------------------

    private fun jsonForPolicy(
        accountRef: String,
        minLen: Int = 16,
        maxLen: Int = 16,
        requireUpper: Boolean = true,
        requireLower: Boolean = true,
        requireDigit: Boolean = true,
        requireSymbol: Boolean = false,
        allowedSymbols: String = "",
        forbidWhitespace: Boolean = true,
        forbiddenSubstrings: List<String> = emptyList()
    ): String {
        val forbiddenJson = forbiddenSubstrings.joinToString(",") { "\"$it\"" }
        return """
        {
          "schemaVersion": 1,
          "entries": [
            {
              "enrolledOrigin": "https://example.com",
              "accountReference": "$accountRef",
              "compatibilityProfileVersion": 1,
              "encoderCounter": 0,
              "policy": {
                "minLen": $minLen,
                "maxLen": $maxLen,
                "requireUpper": $requireUpper,
                "requireLower": $requireLower,
                "requireDigit": $requireDigit,
                "requireSymbol": $requireSymbol,
                "allowedSymbols": "$allowedSymbols",
                "forbidWhitespace": $forbidWhitespace,
                "forbiddenSubstrings": [$forbiddenJson]
              },
              "aliasEvidence": {
                "status": "MANUAL"
              }
            }
          ]
        }
        """.trimIndent()
    }

    @Test
    fun `Finding 1 - policy2 shape with empty allowedSymbols and requireSymbol false is rejected`() {
        val json = jsonForPolicy(
            accountRef = "policy2",
            minLen = 16,
            maxLen = 16,
            requireUpper = true,
            requireLower = true,
            requireDigit = true,
            requireSymbol = false,
            allowedSymbols = "",
            forbidWhitespace = true
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Policy with requireSymbol=false and empty allowedSymbols must be rejected due to browser/Rust divergence"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertTrue(rejection is CatalogRejection.MalformedPolicy)
        assertEquals(MalformedPolicyCode.EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL, (rejection as CatalogRejection.MalformedPolicy).code)
        assertEquals(0, rejection.entryIndex)
    }

    @Test
    fun `Finding 1 - policy3 shape with empty allowedSymbols and requireSymbol false without digits is rejected`() {
        val json = jsonForPolicy(
            accountRef = "policy3",
            minLen = 12,
            maxLen = 12,
            requireUpper = true,
            requireLower = true,
            requireDigit = false,
            requireSymbol = false,
            allowedSymbols = "",
            forbidWhitespace = false
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Policy with requireSymbol=false and empty allowedSymbols must be rejected due to browser/Rust divergence"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertTrue(rejection is CatalogRejection.MalformedPolicy)
        assertEquals(MalformedPolicyCode.EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL, (rejection as CatalogRejection.MalformedPolicy).code)
        assertEquals(0, rejection.entryIndex)
    }

    @Test
    fun `Finding 1 - valid control - requireSymbol false with non-empty allowedSymbols is accepted`() {
        val json = jsonForPolicy(
            accountRef = "control_non_empty_symbols",
            minLen = 16,
            maxLen = 16,
            requireUpper = true,
            requireLower = true,
            requireDigit = true,
            requireSymbol = false,
            allowedSymbols = "!@#$",
            forbidWhitespace = true
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Accepted,
            "Policy with requireSymbol=false and non-empty allowedSymbols must be accepted"
        )
    }

    @Test
    fun `Finding 1 - valid control - requireSymbol true with allowedSymbols is accepted`() {
        val json = jsonForPolicy(
            accountRef = "control_required_symbols",
            minLen = 20,
            maxLen = 32,
            requireUpper = true,
            requireLower = true,
            requireDigit = true,
            requireSymbol = true,
            allowedSymbols = "!@#$%^&*",
            forbidWhitespace = true
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Accepted,
            "Policy with requireSymbol=true and valid allowedSymbols must be accepted"
        )
    }

    // -------------------------------------------------------------
    // Finding 2: Unpaired surrogates
    // -------------------------------------------------------------

    @Test
    fun `Finding 2 - raw JSON containing literal lone surrogate is rejected`() {
        val rawJson = jsonForPolicy(
            accountRef = "synthetic\uD800",
            allowedSymbols = "!@#$"
        )
        val result = validator.validate(rawJson)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Literal lone surrogate in raw JSON must be rejected"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertEquals(CatalogRejection.UnpairedSurrogate, rejection)
    }

    @Test
    fun `Finding 2 - JSON containing escaped lone surrogate in account reference is rejected`() {
        // Escaped \uD800 in JSON string: raw JSON contains ASCII \ u D 8 0 0
        val escapedRef = "synthetic" + "\\" + "uD800"
        val json = jsonForPolicy(
            accountRef = escapedRef,
            allowedSymbols = "!@#$"
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Escaped lone surrogate \\uD800 in account reference must be rejected"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertEquals(CatalogRejection.UnpairedSurrogate, rejection)
    }

    @Test
    fun `Finding 2 - AccountReference constructor rejects unpaired surrogate`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountReference("synthetic\uD800")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountReference("\uDC00synthetic")
        }
    }

    @Test
    fun `Finding 2 - valid control - valid surrogate pair emoji in account reference is accepted`() {
        // Emoji grinning face 😀 is \uD83D\uDE00 (valid surrogate pair)
        val ref = AccountReference("user_😀_valid")
        assertEquals("user_😀_valid", ref.value)

        val json = jsonForPolicy(
            accountRef = "user_😀_valid",
            allowedSymbols = "!@#$"
        )
        val result = validator.validate(json)
        assertTrue(
            result is CatalogValidationResult.Accepted,
            "Valid surrogate pair (emoji) must be accepted"
        )
    }
}

