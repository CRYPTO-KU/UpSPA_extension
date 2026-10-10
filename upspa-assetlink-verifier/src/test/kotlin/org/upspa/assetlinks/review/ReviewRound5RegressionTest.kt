package org.upspa.assetlinks.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.fetcher.AssetLinkFetcher
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.fetcher.FetchResult
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.model.RequestedIdentity
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.PureAssetLinkVerifier
import org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifier
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.stream.Stream

class ReviewRound5RegressionTest {

    companion object {
        private const val HEX_A = "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA"
        private const val HEX_B = "BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB"

        private val VALID_STATEMENT = """
            {
              "relation": ["delegate_permission/common.handle_all_urls"],
              "target": {
                "namespace": "android_app",
                "package_name": "com.example.app",
                "sha256_cert_fingerprints": ["$HEX_A"]
              }
            }
        """.trimIndent()

        @JvmStatic
        fun malformedJsonInputs(): Stream<Arguments> = Stream.of(
            Arguments.of("array_containing_null", "[null]"),
            Arguments.of("valid_followed_by_null", "[$VALID_STATEMENT, null]"),
            Arguments.of("null_followed_by_valid", "[null, $VALID_STATEMENT]"),
            Arguments.of(
                "relation_contains_null",
                """
                [
                  {
                    "relation": [null],
                    "target": {
                      "namespace": "android_app",
                      "package_name": "com.example.app",
                      "sha256_cert_fingerprints": ["$HEX_A"]
                    }
                  }
                ]
                """.trimIndent()
            ),
            Arguments.of(
                "fingerprints_contains_null",
                """
                [
                  {
                    "relation": ["delegate_permission/common.handle_all_urls"],
                    "target": {
                      "namespace": "android_app",
                      "package_name": "com.example.app",
                      "sha256_cert_fingerprints": [null]
                    }
                  }
                ]
                """.trimIndent()
            ),
            Arguments.of(
                "target_is_null",
                """
                [
                  {
                    "relation": ["delegate_permission/common.handle_all_urls"],
                    "target": null
                  }
                ]
                """.trimIndent()
            ),
            Arguments.of("statement_is_string", """["this is a json string instead of an object"]""")
        )

        @JvmStatic
        fun hostParityCases(): Stream<String> = Stream.of(
            "[::1]",
            "[2001:db8::1]",
            "[2001:DB8::1]",
            "[abcd]",
            "[:::]",
            "[12345::]",
            "[::1",
            "[]",
            "[g::1]",
            "[::ffff:1.2.3.4]",
            "example.com",
            "127.0.0.1",
            "a-b.example.com"
        )
    }

    /**
     * Test double counting invocations of fetchAssetLinks.
     */
    private class CountingAssetLinkFetcher(
        private val delegate: AssetLinkFetcher
    ) : AssetLinkFetcher {
        var fetchCount: Int = 0
            private set

        override fun fetchAssetLinks(origin: Origin): FetchResult {
            fetchCount++
            return delegate.fetchAssetLinks(origin)
        }
    }

    // =========================================================================
    // Issue 1: Rotation lineage repair vs rejection
    // =========================================================================

    @Test
    @DisplayName("ISSUE1_fromTypedValues_rejects_inconsistent_history")
    fun `ISSUE1_fromTypedValues_rejects_inconsistent_history`() {
        val pkg = AndroidPackageName.of("com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)

        assertThrows(IllegalArgumentException::class.java) {
            AppSigningInfo.fromTypedValues(
                packageIdentity = pkg,
                currentSigners = listOf(certB),
                rotationHistory = listOf(certA)
            )
        }
    }

    @Test
    @DisplayName("ISSUE1_end_to_end_statement_authorizing_A_never_verifies_inconsistent_history_app")
    fun `ISSUE1_end_to_end_statement_authorizing_A_never_verifies_inconsistent_history_app`() {
        val origin = Origin.parse("https://example.com")
        val pkg = AndroidPackageName.of("com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)

        val statementsJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()

        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, statementsJson))

        // If fromTypedValues improperly succeeds by silently repairing history ([A] -> [A, B]),
        // verification against a statement authorizing only A must NOT yield Verified!
        val app = try {
            AppSigningInfo.fromTypedValues(
                packageIdentity = pkg,
                currentSigners = listOf(certB),
                rotationHistory = listOf(certA)
            )
        } catch (_: IllegalArgumentException) {
            null
        }

        if (app != null) {
            val result = verifier.verify(origin, app)
            assertFalse(result is VerificationResult.Verified, "Repaired app must never verify against old key A")
        }
    }

    @Test
    @DisplayName("ISSUE1_control_no_history_verifies_against_current_signer")
    fun `ISSUE1_control_no_history_verifies_against_current_signer`() {
        val origin = Origin.parse("https://example.com")
        val pkg = AndroidPackageName.of("com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)

        val app = AppSigningInfo.fromTypedValues(pkg, listOf(certA))
        val statementsJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()

        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, statementsJson))
        val result = verifier.verify(origin, app)
        assertTrue(result is VerificationResult.Verified)
    }

    @Test
    @DisplayName("ISSUE1_control_consistent_history_constructs_and_verifies")
    fun `ISSUE1_control_consistent_history_constructs_and_verifies`() {
        val origin = Origin.parse("https://example.com")
        val pkg = AndroidPackageName.of("com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val certB = CertificateDigest.fromHex(HEX_B)

        // Valid lineage: rotated from A to B, history contains both [A, B]
        val app = AppSigningInfo.fromTypedValues(pkg, listOf(certB), listOf(certA, certB))
        val statementsJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()

        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, statementsJson))
        val result = verifier.verify(origin, app)
        assertTrue(result is VerificationResult.Verified)
    }

    // =========================================================================
    // Issue 2: Requested package binding
    // =========================================================================

    @Test
    @DisplayName("ISSUE2_requested_package_mismatch_must_reject_and_never_fetch")
    fun `ISSUE2_requested_package_mismatch_must_reject_and_never_fetch`() {
        val origin = Origin.parse("https://example.com")
        val requestedIdentity = RequestedIdentity.of(origin, "com.requested.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val appSigningInfo = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))

        val statementsJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()

        val countingFetcher = CountingAssetLinkFetcher(FakeAssetLinkFetcher.withJson(origin, statementsJson))
        val verifier = UpSpaAssetLinkVerifier(countingFetcher)

        val result = verifier.verify(requestedIdentity, appSigningInfo)

        assertTrue(result is VerificationResult.Rejected, "Result must be Rejected")
        assertFalse(result is VerificationResult.Verified, "Result must not be Verified")
        assertEquals(0, countingFetcher.fetchCount, "Fetcher must not be called when requested package mismatches signing info")
    }

    @Test
    @DisplayName("ISSUE2_control_matching_package_verifies_and_fetches")
    fun `ISSUE2_control_matching_package_verifies_and_fetches`() {
        val origin = Origin.parse("https://example.com")
        val requestedIdentity = RequestedIdentity.of(origin, "com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val appSigningInfo = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))

        val statementsJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()

        val countingFetcher = CountingAssetLinkFetcher(FakeAssetLinkFetcher.withJson(origin, statementsJson))
        val verifier = UpSpaAssetLinkVerifier(countingFetcher)

        val result = verifier.verify(requestedIdentity, appSigningInfo)

        assertTrue(result is VerificationResult.Verified, "Matching package must verify")
        assertEquals(1, countingFetcher.fetchCount, "Fetcher must be called once")
    }

    // =========================================================================
    // Issue 3: Null elements in parsed JSON
    // =========================================================================

    @ParameterizedTest(name = "ISSUE3_pure_verifier_{0}")
    @MethodSource("malformedJsonInputs")
    @DisplayName("ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json")
    fun `ISSUE3_pure_verifier_returns_InvalidJsonFormat_for_malformed_json`(name: String, json: String) {
        val origin = Origin.parse("https://example.com")
        val certA = CertificateDigest.fromHex(HEX_A)
        val app = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))
        val pureVerifier = PureAssetLinkVerifier()

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result, "Failed for input: $name")
    }

    @ParameterizedTest(name = "ISSUE3_upspa_verifier_{0}")
    @MethodSource("malformedJsonInputs")
    @DisplayName("ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json")
    fun `ISSUE3_upspa_verifier_returns_InvalidJsonFormat_for_malformed_json`(name: String, json: String) {
        val origin = Origin.parse("https://example.com")
        val certA = CertificateDigest.fromHex(HEX_A)
        val app = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))
        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, json))

        val result = verifier.verify(origin, app)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result, "Failed for input: $name")
    }

    // =========================================================================
    // Issue 4: Certificate bytes factory validation
    // =========================================================================

    @Test
    @DisplayName("ISSUE4_fromCertificateBytes_rejects_raw_invalid_bytes")
    fun `ISSUE4_fromCertificateBytes_rejects_raw_invalid_bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromCertificateBytes(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    @DisplayName("ISSUE4_fromCertificateBytes_rejects_empty_bytes")
    fun `ISSUE4_fromCertificateBytes_rejects_empty_bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromCertificateBytes(byteArrayOf())
        }
    }

    @Test
    @DisplayName("ISSUE4_fromCertificateBytes_rejects_appended_trailing_bytes")
    fun `ISSUE4_fromCertificateBytes_rejects_appended_trailing_bytes`() {
        val bytes = CertificateUtils.sampleX509CertificateBytes + byteArrayOf(1, 2, 3)
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromCertificateBytes(bytes)
        }
    }

    @Test
    @DisplayName("ISSUE4_fromCertificateBytes_rejects_truncated_bytes")
    fun `ISSUE4_fromCertificateBytes_rejects_truncated_bytes`() {
        val bytes = CertificateUtils.sampleX509CertificateBytes.copyOf(CertificateUtils.sampleX509CertificateBytes.size - 10)
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromCertificateBytes(bytes)
        }
    }

    @Test
    @DisplayName("ISSUE4_control_valid_sample_certificate_accepted")
    fun `ISSUE4_control_valid_sample_certificate_accepted`() {
        val sampleBytes = CertificateUtils.sampleX509CertificateBytes
        val digest = CertificateDigest.fromCertificateBytes(sampleBytes)
        val expected = CertificateUtils.computeSha256Fingerprint(sampleBytes)
        assertEquals(expected, digest.value)

        val factory = CertificateFactory.getInstance("X.509")
        val x509 = factory.generateCertificate(ByteArrayInputStream(sampleBytes)) as X509Certificate
        val digestFromX509 = CertificateDigest.fromX509Certificate(x509)
        assertEquals(expected, digestFromX509.value)
    }

    // =========================================================================
    // Issue 5: Origin IPv6 host validation parity
    // =========================================================================

    @Test
    @DisplayName("ISSUE5_direct_constructor_rejects_invalid_ipv6_literal")
    fun `ISSUE5_direct_constructor_rejects_invalid_ipv6_literal`() {
        assertThrows(IllegalArgumentException::class.java) {
            Origin("https", "[abcd]", 443)
        }
    }

    @ParameterizedTest(name = "ISSUE5_host_parity_{0}")
    @MethodSource("hostParityCases")
    @DisplayName("ISSUE5_host_validation_parity_between_constructor_and_parse")
    fun `ISSUE5_host_validation_parity_between_constructor_and_parse`(host: String) {
        // Documented allow-list for differences unrelated to IPv6 literal syntax (empty for this round):
        val nonIpv6AllowList = emptySet<String>()
        if (host in nonIpv6AllowList) return

        val ctorAccepted = try {
            Origin("https", host, 443)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        val parseAccepted = try {
            Origin.parse("https://$host")
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        assertEquals(
            parseAccepted,
            ctorAccepted,
            "Parity mismatch for host '$host': constructor accepted=$ctorAccepted, parse accepted=$parseAccepted"
        )
    }
}
