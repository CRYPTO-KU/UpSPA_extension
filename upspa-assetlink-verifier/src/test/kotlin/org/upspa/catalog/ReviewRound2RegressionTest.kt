package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.upspa.assetlinks.model.EnrolledOrigin

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

    // -------------------------------------------------------------
    // Finding 3: Literal newline/control chars inside JSON strings
    // -------------------------------------------------------------

    @Test
    fun `Finding 3 - literal newline inside JSON string is rejected as MalformedJson`() {
        val jsonWithLiteralNewline = jsonForPolicy(
            accountRef = "user1",
            allowedSymbols = "!\n@",
            forbidWhitespace = false
        )
        val result = validator.validate(jsonWithLiteralNewline)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Literal newline inside JSON string must be rejected"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertEquals(CatalogRejection.MalformedJson, rejection)
    }

    @Test
    fun `Finding 3 - literal tab inside JSON string is rejected as MalformedJson`() {
        val jsonWithLiteralTab = jsonForPolicy(
            accountRef = "user1",
            allowedSymbols = "!\t@",
            forbidWhitespace = false
        )
        val result = validator.validate(jsonWithLiteralTab)
        assertTrue(
            result is CatalogValidationResult.Rejected,
            "Literal tab inside JSON string must be rejected"
        )
        val rejection = (result as CatalogValidationResult.Rejected).rejection
        assertEquals(CatalogRejection.MalformedJson, rejection)
    }

    @Test
    fun `Finding 3 - valid control - escaped newline and tab inside JSON string remain valid`() {
        // Escaped \n and \t in allowedSymbols
        val jsonWithEscapes = jsonForPolicy(
            accountRef = "user1",
            allowedSymbols = "!\\n\\t@",
            forbidWhitespace = false
        )
        val result = validator.validate(jsonWithEscapes)
        assertTrue(
            result is CatalogValidationResult.Accepted,
            "Escaped \\n and \\t inside string must be accepted when forbidWhitespace=false"
        )
    }

    @Test
    fun `Finding 3 - valid control - whitespace between JSON tokens remains allowed`() {
        val prettyJson = """
        {
        	"schemaVersion": 1,
        	"entries": [
        		{
        			"enrolledOrigin": "https://example.com",
        			"accountReference": "user_tokens",
        			"compatibilityProfileVersion": 1,
        			"encoderCounter": 0,
        			"policy": {
        				"minLen": 20,
        				"maxLen": 32,
        				"requireUpper": true,
        				"requireLower": true,
        				"requireDigit": true,
        				"requireSymbol": false,
        				"allowedSymbols": "!@#$",
        				"forbidWhitespace": true,
        				"forbiddenSubstrings": []
        			},
        			"aliasEvidence": {
        				"status": "MANUAL"
        			}
        		}
        	]
        }
        """.trimIndent()
        val result = validator.validate(prettyJson)
        assertTrue(
            result is CatalogValidationResult.Accepted,
            "Whitespace outside of JSON strings between tokens must be accepted"
        )
    }

    // -------------------------------------------------------------
    // Finding 4: CatalogDocument and value types mutable collection snapshot
    // -------------------------------------------------------------

    private val sampleEntry = CatalogEntry(
        enrolledOrigin = EnrolledOrigin.parse("https://example.com"),
        accountReference = AccountReference("alice"),
        policy = NormalizedPasswordPolicy(
            minLen = 20,
            maxLen = 32,
            requireUpper = true,
            requireLower = true,
            requireDigit = true,
            requireSymbol = true,
            allowedSymbols = "!@#$%^&*",
            forbidWhitespace = true,
            forbiddenSubstrings = emptyList()
        ),
        encoderCounter = EncoderCounter(0L),
        compatibilityProfileVersion = CompatibilityProfileVersion(1),
        aliasEvidence = AliasEvidence(AliasEvidenceStatus.MANUAL)
    )

    @Test
    fun `Finding 4 - caller mutating entries list after CatalogDocument construction does not affect document or bypass uniqueness`() {
        val mutableEntries = mutableListOf(sampleEntry)
        val doc = CatalogDocument(CatalogSchemaVersion(1), mutableEntries)
        assertEquals(1, doc.entries.size)

        // Caller mutates the list passed to constructor: appends duplicate entry with different counter
        val duplicateEntry = sampleEntry.copy(encoderCounter = EncoderCounter(1L))
        mutableEntries.add(duplicateEntry)

        // Document MUST NOT be mutated!
        assertEquals(1, doc.entries.size, "CatalogDocument entries must remain unchanged after caller list mutation")
        assertFalse(doc.entries.contains(duplicateEntry), "Document must not contain appended duplicate entry")
    }

    @Test
    fun `Finding 4 - caller mutating forbiddenSubstrings after NormalizedPasswordPolicy construction does not affect policy`() {
        val mutableSubs = mutableListOf("admin")
        val policy = NormalizedPasswordPolicy(
            minLen = 20,
            maxLen = 32,
            requireUpper = true,
            requireLower = true,
            requireDigit = true,
            requireSymbol = true,
            allowedSymbols = "!@#$",
            forbidWhitespace = true,
            forbiddenSubstrings = mutableSubs
        )
        assertEquals(1, policy.forbiddenSubstrings.size)

        mutableSubs.add("INVALID_UPPERCASE")
        assertEquals(1, policy.forbiddenSubstrings.size, "Policy forbiddenSubstrings must remain unchanged after caller list mutation")
        assertFalse(policy.forbiddenSubstrings.contains("INVALID_UPPERCASE"))
    }

    @Test
    fun `Finding 4 - valid control - duplicate entry at construction time is rejected`() {
        val duplicateEntry = sampleEntry.copy(encoderCounter = EncoderCounter(1L))
        assertThrows(IllegalArgumentException::class.java) {
            CatalogDocument(CatalogSchemaVersion(1), listOf(sampleEntry, duplicateEntry))
        }
    }

    // -------------------------------------------------------------
    // Finding 5: scripts/mutation-check.sh bash syntax check
    // -------------------------------------------------------------

    private fun isBashOnPath(): Boolean {
        return try {
            val process = ProcessBuilder("bash", "--version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun `Finding 5 - valid control - mutation check script exists and has valid bash syntax`() {
        val scriptFile = java.io.File("scripts/mutation-check.sh").canonicalFile
        assertTrue(scriptFile.exists(), "scripts/mutation-check.sh must exist")

        org.junit.jupiter.api.Assumptions.assumeTrue(isBashOnPath(), "bash was not found on PATH; skipping bash syntax check")

        val process = ProcessBuilder("bash", "-n", scriptFile.path)
            .directory(scriptFile.parentFile.parentFile)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "scripts/mutation-check.sh must be valid bash syntax. Output:\n$output")
    }
}




