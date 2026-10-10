package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
}
