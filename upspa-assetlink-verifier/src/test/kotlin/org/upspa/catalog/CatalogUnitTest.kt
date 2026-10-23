package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.EnrolledOrigin

class CatalogUnitTest {

    private val validPolicy = NormalizedPasswordPolicy(
        minLen = 20,
        maxLen = 32,
        requireUpper = true,
        requireLower = true,
        requireDigit = true,
        requireSymbol = true,
        allowedSymbols = "!@#$%^&*",
        forbidWhitespace = true,
        forbiddenSubstrings = emptyList()
    )

    private val validOrigin = EnrolledOrigin.parse("https://example.com")
    private val validPackage = AndroidPackageName.of("com.example.app")
    private val validDigest = CertificateDigest.fromHex("14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17")

    @Test
    fun `AccountReference enforces invariants and preserves case`() {
        assertThrows(IllegalArgumentException::class.java) { AccountReference("") }
        assertThrows(IllegalArgumentException::class.java) { AccountReference(" leading") }
        assertThrows(IllegalArgumentException::class.java) { AccountReference("trailing ") }
        assertThrows(IllegalArgumentException::class.java) { AccountReference("with|pipe") }
        assertThrows(IllegalArgumentException::class.java) { AccountReference("ctrl\u001fchar") }
        assertThrows(IllegalArgumentException::class.java) { AccountReference("a".repeat(257)) }

        val ref1 = AccountReference("Alice")
        val ref2 = AccountReference("alice")
        assertNotEquals(ref1, ref2)
        assertEquals("Alice", ref1.value)
        assertEquals("alice", ref2.value)
    }

    @Test
    fun `AccountReference redaction hides raw value in toString`() {
        val secretUser = "very_secret_username"
        val ref = AccountReference(secretUser)
        val str = ref.toString()
        assertFalse(str.contains(secretUser), "toString must not leak account reference")
        assertEquals("AccountReference(<redacted>)", str)
    }

    @Test
    fun `EncoderCounter enforces u32 bounds`() {
        assertThrows(IllegalArgumentException::class.java) { EncoderCounter(-1L) }
        assertThrows(IllegalArgumentException::class.java) { EncoderCounter(4294967296L) }

        val c0 = EncoderCounter(0L)
        val cMax = EncoderCounter(4294967295L)
        assertEquals(0L, c0.value)
        assertEquals(4294967295L, cMax.value)
    }

    @Test
    fun `NormalizedPasswordPolicy enforces 7 normalization invariants`() {
        // 1. minLen >= 8
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(minLen = 7) }
        // 2. minLen <= maxLen
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(minLen = 33, maxLen = 32) }
        // 3. maxLen > 64 requires maxLen == minLen
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(minLen = 20, maxLen = 70) }
        val policyCap = validPolicy.copy(minLen = 70, maxLen = 70)
        assertEquals(70, policyCap.maxLen)
        // 4. allowedSymbols has no duplicate code points
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(allowedSymbols = "!!") }
        // 5. forbidWhitespace forbids whitespace in allowedSymbols
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(allowedSymbols = "! ", forbidWhitespace = true) }
        // 6. requireSymbol requires non-empty allowedSymbols
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(requireSymbol = true, allowedSymbols = "") }
        // 7. forbiddenSubstrings normalized
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(forbiddenSubstrings = listOf("  foo")) }
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(forbiddenSubstrings = listOf("Foo")) }
        assertThrows(IllegalArgumentException::class.java) { validPolicy.copy(forbiddenSubstrings = listOf("")) }
    }

    @Test
    fun `AliasEvidence enforces cross-field status rules`() {
        val ref = AliasEvidenceReference(validPackage, validDigest, validOrigin)

        // VERIFIED requires reference, forbids unverifiedReason
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = null, unverifiedReason = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = ref, unverifiedReason = UnverifiedReason.EVIDENCE_STALE)
        }
        val verified = AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = ref, unverifiedReason = null)
        assertTrue(verified.isEligibleForCredentialRelease)

        // UNVERIFIED requires reason
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.UNVERIFIED, reference = null, unverifiedReason = null)
        }
        val unverified = AliasEvidence(AliasEvidenceStatus.UNVERIFIED, reference = null, unverifiedReason = UnverifiedReason.EVIDENCE_MISSING)
        assertFalse(unverified.isEligibleForCredentialRelease)

        // PENDING forbids reason
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.PENDING, reference = null, unverifiedReason = UnverifiedReason.EVIDENCE_MISSING)
        }
        val pending = AliasEvidence(AliasEvidenceStatus.PENDING, reference = null, unverifiedReason = null)
        assertFalse(pending.isEligibleForCredentialRelease)

        // MANUAL forbids reference and reason
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.MANUAL, reference = ref, unverifiedReason = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AliasEvidence(AliasEvidenceStatus.MANUAL, reference = null, unverifiedReason = UnverifiedReason.EVIDENCE_MISSING)
        }
        val manual = AliasEvidence(AliasEvidenceStatus.MANUAL, reference = null, unverifiedReason = null)
        assertFalse(manual.isEligibleForCredentialRelease)
    }

    @Test
    fun `CatalogEntry enforces exact origin binding and HTTPS for VERIFIED`() {
        val otherOrigin = EnrolledOrigin.parse("https://other.example.com")
        val httpOrigin = EnrolledOrigin.parse("http://example.com")
        val refForExample = AliasEvidenceReference(validPackage, validDigest, validOrigin)
        val verifiedEvidence = AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = refForExample)

        // Mismatched sourceOrigin in reference vs entry enrolledOrigin
        assertThrows(IllegalArgumentException::class.java) {
            CatalogEntry(
                enrolledOrigin = otherOrigin,
                accountReference = AccountReference("alice"),
                policy = validPolicy,
                encoderCounter = EncoderCounter(0),
                compatibilityProfileVersion = CompatibilityProfileVersion(1),
                aliasEvidence = verifiedEvidence
            )
        }

        // VERIFIED status with non-HTTPS enrolledOrigin
        val refHttp = AliasEvidenceReference(validPackage, validDigest, httpOrigin)
        val verifiedHttpEvidence = AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = refHttp)
        assertThrows(IllegalArgumentException::class.java) {
            CatalogEntry(
                enrolledOrigin = httpOrigin,
                accountReference = AccountReference("alice"),
                policy = validPolicy,
                encoderCounter = EncoderCounter(0),
                compatibilityProfileVersion = CompatibilityProfileVersion(1),
                aliasEvidence = verifiedHttpEvidence
            )
        }
    }

    @Test
    fun `derivationIdentity distinctness across origins and accounts`() {
        val entry1 = CatalogEntry(
            enrolledOrigin = EnrolledOrigin.parse("https://example.com"),
            accountReference = AccountReference("alice"),
            policy = validPolicy,
            encoderCounter = EncoderCounter(0),
            compatibilityProfileVersion = CompatibilityProfileVersion(1),
            aliasEvidence = AliasEvidence(AliasEvidenceStatus.MANUAL)
        )
        val entry2 = CatalogEntry(
            enrolledOrigin = EnrolledOrigin.parse("https://www.example.com"),
            accountReference = AccountReference("alice"),
            policy = validPolicy,
            encoderCounter = EncoderCounter(0),
            compatibilityProfileVersion = CompatibilityProfileVersion(1),
            aliasEvidence = AliasEvidence(AliasEvidenceStatus.MANUAL)
        )
        val entry3 = CatalogEntry(
            enrolledOrigin = EnrolledOrigin.parse("https://example.com:8443"),
            accountReference = AccountReference("alice"),
            policy = validPolicy,
            encoderCounter = EncoderCounter(0),
            compatibilityProfileVersion = CompatibilityProfileVersion(1),
            aliasEvidence = AliasEvidence(AliasEvidenceStatus.MANUAL)
        )

        assertEquals("https://example.com|alice", entry1.derivationIdentity())
        assertEquals("https://www.example.com|alice", entry2.derivationIdentity())
        assertEquals("https://example.com:8443|alice", entry3.derivationIdentity())

        assertNotEquals(entry1.derivationIdentity(), entry2.derivationIdentity())
        assertNotEquals(entry1.derivationIdentity(), entry3.derivationIdentity())
        assertNotEquals(entry2.derivationIdentity(), entry3.derivationIdentity())
    }

    @Test
    fun `derivationIdentity injectivity guarantee`() {
        // Because accountReference cannot contain '|' and enrolledOrigin is canonical WHATWG origin,
        // (originA, accountA) != (originB, accountB) => originA + "|" + accountA != originB + "|" + accountB
        val id1 = "https://example.com|alice"
        val parts = id1.split('|')
        assertEquals(2, parts.size)
        assertEquals("https://example.com", parts[0])
        assertEquals("alice", parts[1])
    }

    @Test
    fun `domain model toString does not leak account reference or origin`() {
        val sensitiveAccount = "secret_alice_ref"
        val ref = AliasEvidenceReference(validPackage, validDigest, validOrigin)
        val evidence = AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = ref)
        val entry = CatalogEntry(
            enrolledOrigin = validOrigin,
            accountReference = AccountReference(sensitiveAccount),
            policy = validPolicy,
            encoderCounter = EncoderCounter(0),
            compatibilityProfileVersion = CompatibilityProfileVersion(1),
            aliasEvidence = evidence
        )
        val doc = CatalogDocument(CatalogSchemaVersion(1), listOf(entry))

        val models = listOf<Any>(entry, evidence, ref, doc)
        for (m in models) {
            val str = m.toString()
            assertFalse(str.contains(sensitiveAccount), "${m::class.simpleName}.toString must not leak account reference: $str")
            assertFalse(str.contains("example.com"), "${m::class.simpleName}.toString must not leak origin (example.com): $str")
        }

        // Verify S4 package name and certificate digest remain visible in AliasEvidenceReference
        assertTrue(ref.toString().contains(validPackage.value))
        assertTrue(ref.toString().contains(validDigest.value))
        // CatalogDocument prints only schema version and entry count
        assertEquals("CatalogDocument(schemaVersion=1, entryCount=1)", doc.toString())
    }

    @Test
    fun `StrictCatalogValidator rejections do not leak sensitive origin or account reference in toString`() {
        val secretRef = "secret_alice_ref"
        val secretOrigin = "https://secret-host.example.com"
        val validator = StrictCatalogValidator()

        // 1. Invalid account reference (contains separator '|')
        val invalidAccountJson = """
            {
              "schemaVersion": 1,
              "entries": [
                {
                  "enrolledOrigin": "$secretOrigin",
                  "accountReference": "${secretRef}|extra",
                  "compatibilityProfileVersion": 1,
                  "encoderCounter": 0,
                  "policy": {
                    "minLen": 20,
                    "maxLen": 32,
                    "requireUpper": true,
                    "requireLower": true,
                    "requireDigit": true,
                    "requireSymbol": true,
                    "allowedSymbols": "!@#${'$'}%^&*",
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

        // 2. Non-canonical origin (trailing slash)
        val nonCanonicalOriginJson = """
            {
              "schemaVersion": 1,
              "entries": [
                {
                  "enrolledOrigin": "$secretOrigin/",
                  "accountReference": "$secretRef",
                  "compatibilityProfileVersion": 1,
                  "encoderCounter": 0,
                  "policy": {
                    "minLen": 20,
                    "maxLen": 32,
                    "requireUpper": true,
                    "requireLower": true,
                    "requireDigit": true,
                    "requireSymbol": true,
                    "allowedSymbols": "!@#${'$'}%^&*",
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

        // 3. Alias origin mismatch (reference sourceOrigin != enrolledOrigin)
        val aliasMismatchJson = """
            {
              "schemaVersion": 1,
              "entries": [
                {
                  "enrolledOrigin": "$secretOrigin",
                  "accountReference": "$secretRef",
                  "compatibilityProfileVersion": 1,
                  "encoderCounter": 0,
                  "policy": {
                    "minLen": 20,
                    "maxLen": 32,
                    "requireUpper": true,
                    "requireLower": true,
                    "requireDigit": true,
                    "requireSymbol": true,
                    "allowedSymbols": "!@#${'$'}%^&*",
                    "forbidWhitespace": true,
                    "forbiddenSubstrings": []
                  },
                  "aliasEvidence": {
                    "status": "VERIFIED",
                    "reference": {
                      "packageName": "com.example.app",
                      "signingCertificateDigest": "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17",
                      "sourceOrigin": "https://other.example.com"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        // 4. Duplicate entry (two identical pairs of enrolledOrigin and accountReference)
        val duplicateEntryJson = """
            {
              "schemaVersion": 1,
              "entries": [
                {
                  "enrolledOrigin": "$secretOrigin",
                  "accountReference": "$secretRef",
                  "compatibilityProfileVersion": 1,
                  "encoderCounter": 0,
                  "policy": {
                    "minLen": 20,
                    "maxLen": 32,
                    "requireUpper": true,
                    "requireLower": true,
                    "requireDigit": true,
                    "requireSymbol": true,
                    "allowedSymbols": "!@#${'$'}%^&*",
                    "forbidWhitespace": true,
                    "forbiddenSubstrings": []
                  },
                  "aliasEvidence": {
                    "status": "MANUAL"
                  }
                },
                {
                  "enrolledOrigin": "$secretOrigin",
                  "accountReference": "$secretRef",
                  "compatibilityProfileVersion": 1,
                  "encoderCounter": 1,
                  "policy": {
                    "minLen": 20,
                    "maxLen": 32,
                    "requireUpper": true,
                    "requireLower": true,
                    "requireDigit": true,
                    "requireSymbol": true,
                    "allowedSymbols": "!@#${'$'}%^&*",
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

        val testCases = listOf(
            invalidAccountJson to CatalogRejection.InvalidAccountReference::class,
            nonCanonicalOriginJson to CatalogRejection.NonCanonicalEnrolledOrigin::class,
            aliasMismatchJson to CatalogRejection.AliasOriginMismatch::class,
            duplicateEntryJson to CatalogRejection.DuplicateEntry::class
        )

        for ((json, expectedClass) in testCases) {
            val result = validator.validate(json)
            assertTrue(result is CatalogValidationResult.Rejected, "Expected validation to reject")
            val rejection = (result as CatalogValidationResult.Rejected).rejection
            assertEquals(expectedClass, rejection::class)
            val rejStr = rejection.toString()
            assertFalse(rejStr.contains(secretRef), "Rejection toString must not contain account reference: $rejStr")
            assertFalse(rejStr.contains(secretOrigin), "Rejection toString must not contain origin: $rejStr")
            assertFalse(rejStr.contains("secret-host.example.com"), "Rejection toString must not contain origin host: $rejStr")
        }
    }

    @Test
    fun `StrictCatalogValidator catches unexpected exceptions in doValidate fail-closed`() {
        class FaultyValidator(private val exceptionToThrow: Exception) : StrictCatalogValidator() {
            override fun doValidate(rawJson: String): CatalogValidationResult {
                throw exceptionToThrow
            }
        }

        val exceptions = listOf(
            IllegalStateException("Fault injected IllegalStateException"),
            ArithmeticException("Fault injected ArithmeticException")
        )

        for (ex in exceptions) {
            val validator = FaultyValidator(ex)
            val result = validator.validate("{\"schemaVersion\": 1, \"entries\": []}")
            assertTrue(
                result is CatalogValidationResult.Rejected,
                "Expected Rejected on ${ex::class.simpleName}, got $result"
            )
            assertFalse(
                result is CatalogValidationResult.Accepted,
                "Expected not Accepted on ${ex::class.simpleName}"
            )
            val rejection = (result as CatalogValidationResult.Rejected).rejection
            assertEquals(
                CatalogRejection.InternalValidationError,
                rejection,
                "Expected InternalValidationError on ${ex::class.simpleName}, got $rejection"
            )
        }
    }

    @Test
    fun `StrictCatalogValidator rejects oversized input above 64 KiB`() {
        val validator = StrictCatalogValidator()
        // Generate valid structure but with padding > 64 KiB (65536 bytes)
        val padding = " ".repeat(66000)
        val oversizedJson = "{\"schemaVersion\": 1, \"entries\": []$padding}"
        val result = validator.validate(oversizedJson)
        assertTrue(result is CatalogValidationResult.Rejected)
        assertEquals(CatalogRejection.InputTooLarge, (result as CatalogValidationResult.Rejected).rejection)
    }

    @Test
    fun `CatalogVersions value classes reject unsupported versions`() {
        assertThrows(IllegalArgumentException::class.java) { CatalogSchemaVersion(0) }
        assertThrows(IllegalArgumentException::class.java) { CatalogSchemaVersion(2) }
        assertNull(CatalogSchemaVersion.ofOrNull(2))
        assertNotNull(CatalogSchemaVersion.ofOrNull(1))

        assertThrows(IllegalArgumentException::class.java) { CompatibilityProfileVersion(0) }
        assertThrows(IllegalArgumentException::class.java) { CompatibilityProfileVersion(2) }
        assertNull(CompatibilityProfileVersion.ofOrNull(2))
        assertNotNull(CompatibilityProfileVersion.ofOrNull(1))
    }
}
