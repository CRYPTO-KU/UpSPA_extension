package org.upspa.assetlinks.review

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.PureAssetLinkVerifier
import org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifier

/**
 * Review round 6 regression tests targeting:
 *
 * ISSUE P1 - Signing evidence remains mutable after validation.
 * ISSUE P2 - Numeric relation and typed token elements bypass JSON type validation.
 */
class ReviewRound6RegressionTest {

    companion object {
        private const val HEX_A = "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA"
        private const val HEX_B = "BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB"
        private const val PACKAGE_NAME = "com.example.app"
        private const val ORIGIN_URL = "https://example.com"

        private fun makeStatement(
            packageName: String = PACKAGE_NAME,
            fingerprints: List<String> = listOf(HEX_B),
            relationContent: String = "\"delegate_permission/common.handle_all_urls\""
        ): String {
            val fps = fingerprints.joinToString(", ") { "\"$it\"" }
            return """
                [
                  {
                    "relation": [$relationContent],
                    "target": {
                      "namespace": "android_app",
                      "package_name": "$packageName",
                      "sha256_cert_fingerprints": [$fps]
                    }
                  }
                ]
            """.trimIndent()
        }
    }

    private val origin = Origin.parse(ORIGIN_URL)
    private val pureVerifier = PureAssetLinkVerifier()

    // =========================================================================
    // ISSUE P1: Signing evidence remains mutable after validation
    // =========================================================================

    @Test
    @DisplayName("ISSUE1_scenario1_app_owns_immutable_history_snapshot")
    fun `ISSUE1_scenario1_app_owns_immutable_history_snapshot`() {
        val pkg = AndroidPackageName.of(PACKAGE_NAME)
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)
        val callerHistory = mutableListOf(certA, certB)

        val app = AppSigningInfo.fromTypedValues(
            packageIdentity = pkg,
            currentSigners = listOf(certB),
            rotationHistory = callerHistory
        )

        // Caller removes B from the caller's mutable list
        callerHistory.remove(certB)

        // Internal snapshot must retain certB and full lineage
        assertEquals(2, app.rotationHistory.size)
        assertTrue(app.rotationHistory.contains(certB))
        assertEquals(listOf(certA, certB), app.rotationHistory)
    }

    @Test
    @DisplayName("ISSUE1_scenario1_history_mutation_pure_verifier")
    fun `ISSUE1_scenario1_history_mutation_pure_verifier`() {
        val pkg = AndroidPackageName.of(PACKAGE_NAME)
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)
        val callerHistory = mutableListOf(certA, certB)

        val app = AppSigningInfo.fromTypedValues(
            packageIdentity = pkg,
            currentSigners = listOf(certB),
            rotationHistory = callerHistory
        )

        // Caller removes B after construction
        callerHistory.remove(certB)

        // Statement authorizing current signer B must verify against internal snapshot
        val statementB = makeStatement(fingerprints = listOf(HEX_B))
        val result = pureVerifier.verifyRawJson(origin, app, statementB)

        assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(HEX_B, (result as VerificationResult.Verified).matchedFingerprint)
    }

    @Test
    @DisplayName("ISSUE1_scenario1_history_mutation_upspa_verifier")
    fun `ISSUE1_scenario1_history_mutation_upspa_verifier`() {
        val pkg = AndroidPackageName.of(PACKAGE_NAME)
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)
        val callerHistory = mutableListOf(certA, certB)

        val app = AppSigningInfo.fromTypedValues(
            packageIdentity = pkg,
            currentSigners = listOf(certB),
            rotationHistory = callerHistory
        )

        callerHistory.remove(certB)

        val statementB = makeStatement(fingerprints = listOf(HEX_B))
        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, statementB))
        val result = upspaVerifier.verify(origin, app)

        assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(HEX_B, (result as VerificationResult.Verified).matchedFingerprint)
    }

    @Test
    @DisplayName("ISSUE1_scenario2_raw_history_mutation_pure_verifier")
    fun `ISSUE1_scenario2_raw_history_mutation_pure_verifier`() {
        val certB = CertificateDigest.fromHex(HEX_B)
        val rawHistory = mutableListOf<String>()

        val app = AppSigningInfo(
            packageName = PACKAGE_NAME,
            currentFingerprints = setOf(certB),
            rawRotationHistory = rawHistory
        )

        // Later mutation adds raw A, conflicting with typed family
        rawHistory.add(HEX_A)

        // Statement authorizing only A must NOT verify because construction snapshot was empty
        val statementA = makeStatement(fingerprints = listOf(HEX_A))
        val result = pureVerifier.verifyRawJson(origin, app, statementA)

        assertInstanceOf(VerificationResult.Rejected.CertificateMismatch::class.java, result)
    }

    @Test
    @DisplayName("ISSUE1_scenario2_raw_history_mutation_upspa_verifier")
    fun `ISSUE1_scenario2_raw_history_mutation_upspa_verifier`() {
        val certB = CertificateDigest.fromHex(HEX_B)
        val rawHistory = mutableListOf<String>()

        val app = AppSigningInfo(
            packageName = PACKAGE_NAME,
            currentFingerprints = setOf(certB),
            rawRotationHistory = rawHistory
        )

        rawHistory.add(HEX_A)

        val statementA = makeStatement(fingerprints = listOf(HEX_A))
        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, statementA))
        val result = upspaVerifier.verify(origin, app)

        assertInstanceOf(VerificationResult.Rejected.CertificateMismatch::class.java, result)
    }

    @Test
    @DisplayName("ISSUE1_certificate_byte_array_mutation_fails_to_corrupt_app")
    fun `ISSUE1_certificate_byte_array_mutation_fails_to_corrupt_app`() {
        val certBytes = CertificateUtils.sampleX509CertificateBytes.copyOf()
        val certFingerprint = CertificateUtils.computeSha256Fingerprint(certBytes)

        val app = AppSigningInfo(
            packageName = PACKAGE_NAME,
            signingCertificates = listOf(certBytes)
        )

        // Caller mutates the byte array after construction
        certBytes[0] = (certBytes[0].toInt() xor 0xFF).toByte()

        // App's internally held bytes must remain uncorrupted
        assertTrue(app.validateCertificates())

        val statementValid = makeStatement(fingerprints = listOf(certFingerprint))
        val result = pureVerifier.verifyRawJson(origin, app, statementValid)
        assertInstanceOf(VerificationResult.Verified::class.java, result)
    }

    @Test
    @DisplayName("ISSUE1_certificate_history_byte_array_mutation_fails_to_corrupt_app")
    fun `ISSUE1_certificate_history_byte_array_mutation_fails_to_corrupt_app`() {
        val certBytes = CertificateUtils.sampleX509CertificateBytes.copyOf()
        val certFingerprint = CertificateUtils.computeSha256Fingerprint(certBytes)

        val app = AppSigningInfo(
            packageName = PACKAGE_NAME,
            signingCertificates = listOf(certBytes),
            signingCertificateHistory = listOf(certBytes)
        )

        // Mutate caller's byte array after construction
        certBytes[0] = (certBytes[0].toInt() xor 0xFF).toByte()

        assertTrue(app.validateCertificates())

        val statementValid = makeStatement(fingerprints = listOf(certFingerprint))
        val result = pureVerifier.verifyRawJson(origin, app, statementValid)
        assertInstanceOf(VerificationResult.Verified::class.java, result)
    }

    @Test
    @DisplayName("ISSUE1_exposed_getters_return_defensive_copies_and_readonly_collections")
    fun `ISSUE1_exposed_getters_return_defensive_copies_and_readonly_collections`() {
        val certBytes = CertificateUtils.sampleX509CertificateBytes.copyOf()
        val certDigest = CertificateDigest.fromHex(HEX_B)

        val mutableHistory = mutableListOf(certDigest)
        val mutableCurrent = mutableSetOf(certDigest)
        val app = AppSigningInfo(
            packageName = PACKAGE_NAME,
            currentFingerprints = mutableCurrent,
            rotationHistory = mutableHistory
        )

        // Collections returned must be unmodifiable (must throw UnsupportedOperationException)
        assertThrows(UnsupportedOperationException::class.java) {
            (app.rotationHistory as MutableList<CertificateDigest>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (app.currentFingerprints as MutableSet<CertificateDigest>).clear()
        }

        val mutableRawCurrent = mutableListOf(HEX_B)
        val mutableRawHistory = mutableListOf(HEX_B)
        val rawApp = AppSigningInfo(
            packageName = PACKAGE_NAME,
            rawCurrentFingerprints = mutableRawCurrent,
            rawRotationHistory = mutableRawHistory
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (rawApp.rawRotationHistory as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (rawApp.rawCurrentFingerprints as MutableList<String>).clear()
        }

        // Byte array getters must return copies
        val byteApp = AppSigningInfo(
            packageName = PACKAGE_NAME,
            signingCertificates = listOf(certBytes)
        )
        val extractedBytes = byteApp.signingCertificates[0]
        extractedBytes[0] = (extractedBytes[0].toInt() xor 0xFF).toByte()
        val secondFetch = byteApp.signingCertificates[0]
        assertFalse(extractedBytes.contentEquals(secondFetch))
        assertTrue(byteApp.validateCertificates())
    }

    @Test
    @DisplayName("ISSUE1_control_unmutated_history_verifies_historical_key")
    fun `ISSUE1_control_unmutated_history_verifies_historical_key`() {
        val pkg = AndroidPackageName.of(PACKAGE_NAME)
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)

        val app = AppSigningInfo.fromTypedValues(
            packageIdentity = pkg,
            currentSigners = listOf(certB),
            rotationHistory = listOf(certA, certB)
        )

        val statementA = makeStatement(fingerprints = listOf(HEX_A))
        val pureResult = pureVerifier.verifyRawJson(origin, app, statementA)
        assertInstanceOf(VerificationResult.Verified::class.java, pureResult)
        assertEquals(HEX_A, (pureResult as VerificationResult.Verified).matchedFingerprint)

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, statementA))
        val upspaResult = upspaVerifier.verify(origin, app)
        assertInstanceOf(VerificationResult.Verified::class.java, upspaResult)
        assertEquals(HEX_A, (upspaResult as VerificationResult.Verified).matchedFingerprint)
    }

    // =========================================================================
    // ISSUE P2: Numeric and non-string relation elements bypass JSON validation
    // =========================================================================

    @Test
    @DisplayName("ISSUE2_numeric_relation_element_rejected_on_pure_verifier")
    fun `ISSUE2_numeric_relation_element_rejected_on_pure_verifier`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = makeStatement(relationContent = "\"delegate_permission/common.handle_all_urls\", 123")

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    @DisplayName("ISSUE2_numeric_relation_element_rejected_on_upspa_verifier")
    fun `ISSUE2_numeric_relation_element_rejected_on_upspa_verifier`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = makeStatement(relationContent = "\"delegate_permission/common.handle_all_urls\", 123")

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val result = upspaVerifier.verify(origin, app)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "\"delegate_permission/common.handle_all_urls\", true",
        "\"delegate_permission/common.handle_all_urls\", false",
        "\"delegate_permission/common.handle_all_urls\", {\"key\": \"value\"}",
        "\"delegate_permission/common.handle_all_urls\", [\"nested\"]"
    ])
    @DisplayName("ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier")
    fun `ISSUE2_non_string_relation_tokens_rejected_on_pure_verifier`(relationContent: String) {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = makeStatement(relationContent = relationContent)

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    @DisplayName("ISSUE2_numeric_fingerprint_element_rejected")
    fun `ISSUE2_numeric_fingerprint_element_rejected`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": [123]
                }
              }
            ]
        """.trimIndent()

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    @DisplayName("ISSUE2_numeric_namespace_rejected")
    fun `ISSUE2_numeric_namespace_rejected`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": 123,
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    @DisplayName("ISSUE2_numeric_package_name_rejected")
    fun `ISSUE2_numeric_package_name_rejected`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": 123,
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    @DisplayName("ISSUE2_control_valid_all_string_relation_array_verifies")
    fun `ISSUE2_control_valid_all_string_relation_array_verifies`() {
        val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
        val json = makeStatement(relationContent = "\"delegate_permission/common.handle_all_urls\"")

        val pureResult = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Verified::class.java, pureResult)

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val upspaResult = upspaVerifier.verify(origin, app)
        assertInstanceOf(VerificationResult.Verified::class.java, upspaResult)
    }
}
