package org.upspa.assetlinks.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import java.util.stream.Stream

class ReviewRound5TypedOutcomeTest {

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
    }

    // =========================================================================
    // Item 2: Exact typed outcome RequestedPackageMismatch
    // =========================================================================

    @Test
    @DisplayName("test exact typed outcome RequestedPackageMismatch with package details")
    fun `test exact typed outcome RequestedPackageMismatch with package details`() {
        val origin = Origin.parse("https://example.com")
        val requestedIdentity = RequestedIdentity.of(origin, "com.requested.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val appSigningInfo = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))

        val statementsJson = "[$VALID_STATEMENT]"
        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, statementsJson))

        val result = verifier.verify(requestedIdentity, appSigningInfo)

        val mismatch = assertInstanceOf(VerificationResult.Rejected.RequestedPackageMismatch::class.java, result)
        assertEquals("com.requested.app", mismatch.requestedPackage)
        assertEquals("com.example.app", mismatch.signingPackage)
        assertTrue(mismatch.reason.contains("com.requested.app"))
        assertTrue(mismatch.reason.contains("com.example.app"))
    }

    // =========================================================================
    // Item 3: Exact typed outcome InvalidJsonFormat
    // =========================================================================

    @ParameterizedTest(name = "test_exact_InvalidJsonFormat_pure_{0}")
    @MethodSource("malformedJsonInputs")
    @DisplayName("test exact InvalidJsonFormat outcome on pure verifier")
    fun `test exact InvalidJsonFormat outcome on pure verifier`(name: String, json: String) {
        val origin = Origin.parse("https://example.com")
        val certA = CertificateDigest.fromHex(HEX_A)
        val app = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))
        val pureVerifier = PureAssetLinkVerifier()

        val result = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result, "Failed for input: $name")
    }

    @ParameterizedTest(name = "test_exact_InvalidJsonFormat_upspa_{0}")
    @MethodSource("malformedJsonInputs")
    @DisplayName("test exact InvalidJsonFormat outcome on upspa verifier")
    fun `test exact InvalidJsonFormat outcome on upspa verifier`(name: String, json: String) {
        val origin = Origin.parse("https://example.com")
        val certA = CertificateDigest.fromHex(HEX_A)
        val app = AppSigningInfo.fromTypedValues(AndroidPackageName.of("com.example.app"), listOf(certA))
        val verifier = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, json))

        val result = verifier.verify(origin, app)
        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result, "Failed for input: $name")
    }

    // =========================================================================
    // Meta-check: RequestedPackageMismatch is not produced by any other input
    // =========================================================================

    @Test
    @DisplayName("meta-check RequestedPackageMismatch is never produced when package names match")
    fun `meta-check RequestedPackageMismatch is never produced when package names match`() {
        val origin = Origin.parse("https://example.com")
        val pkg = AndroidPackageName.of("com.example.app")
        val certA = CertificateDigest.fromHex(HEX_A)
        val requestedIdentity = RequestedIdentity.of(origin, pkg)
        val appSigningInfo = AppSigningInfo.fromTypedValues(pkg, listOf(certA))

        // 1. Happy path -> Verified
        val validJson = "[$VALID_STATEMENT]"
        val verifierHappy = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, validJson))
        val resultHappy = verifierHappy.verify(requestedIdentity, appSigningInfo)
        assertTrue(resultHappy is VerificationResult.Verified)
        assertFalse(resultHappy is VerificationResult.Rejected.RequestedPackageMismatch)

        // 2. Package not declared in statements -> PackageNotFound
        val otherPkgStatement = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.other.app",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()
        val verifierNotFound = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, otherPkgStatement))
        val resultNotFound = verifierNotFound.verify(requestedIdentity, appSigningInfo)
        assertTrue(resultNotFound is VerificationResult.Rejected.PackageNotFound)
        assertFalse(resultNotFound is VerificationResult.Rejected.RequestedPackageMismatch)

        // 3. Certificate mismatch -> CertificateMismatch
        val certMismatchStatement = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app",
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()
        val verifierCertMismatch = UpSpaAssetLinkVerifier(FakeAssetLinkFetcher.withJson(origin, certMismatchStatement))
        val resultCertMismatch = verifierCertMismatch.verify(requestedIdentity, appSigningInfo)
        assertTrue(resultCertMismatch is VerificationResult.Rejected.CertificateMismatch)
        assertFalse(resultCertMismatch is VerificationResult.Rejected.RequestedPackageMismatch)

        // 4. Multiple APK signers -> MultipleSignersUnsupported
        val multiSignerApp = AppSigningInfo.fromMultiSigners(pkg.value, listOf(HEX_A, HEX_B))
        val resultMulti = verifierHappy.verify(requestedIdentity, multiSignerApp)
        assertTrue(resultMulti is VerificationResult.Rejected.MultipleSignersUnsupported)
        assertFalse(resultMulti is VerificationResult.Rejected.RequestedPackageMismatch)

        // 5. Insecure HTTP origin -> NonHttpsOrigin
        val httpOrigin = Origin.parse("http://example.com")
        val httpIdentity = RequestedIdentity.of(httpOrigin, pkg)
        val resultHttp = verifierHappy.verify(httpIdentity, appSigningInfo)
        assertTrue(resultHttp is VerificationResult.Rejected.NonHttpsOrigin)
        assertFalse(resultHttp is VerificationResult.Rejected.RequestedPackageMismatch)

        // 6. Missing required relation -> MissingRequiredRelation
        val customRelationIdentity = RequestedIdentity.of(origin, pkg, "delegate_permission/custom.relation")
        val resultRelation = verifierHappy.verify(customRelationIdentity, appSigningInfo)
        assertTrue(resultRelation is VerificationResult.Rejected.MissingRequiredRelation)
        assertFalse(resultRelation is VerificationResult.Rejected.RequestedPackageMismatch)

        // 7. HTTP Redirect -> RedirectAttempted
        val redirectFetcher = object : AssetLinkFetcher {
            override fun fetchAssetLinks(origin: Origin): FetchResult =
                FetchResult.Redirect(301, "https://redirect.example.com")
        }
        val resultRedirect = UpSpaAssetLinkVerifier(redirectFetcher).verify(requestedIdentity, appSigningInfo)
        assertTrue(resultRedirect is VerificationResult.Rejected.RedirectAttempted)
        assertFalse(resultRedirect is VerificationResult.Rejected.RequestedPackageMismatch)

        // 8. Server 404 -> FetchFailed
        val errorFetcher = object : AssetLinkFetcher {
            override fun fetchAssetLinks(origin: Origin): FetchResult =
                FetchResult.HttpError(404, "Not Found")
        }
        val resultError = UpSpaAssetLinkVerifier(errorFetcher).verify(requestedIdentity, appSigningInfo)
        assertTrue(resultError is VerificationResult.Rejected.FetchFailed)
        assertFalse(resultError is VerificationResult.Rejected.RequestedPackageMismatch)

        // 9. Invalid Content-Type -> InvalidContentType
        val ctFetcher = object : AssetLinkFetcher {
            override fun fetchAssetLinks(origin: Origin): FetchResult =
                FetchResult.InvalidContentType("text/html")
        }
        val resultCt = UpSpaAssetLinkVerifier(ctFetcher).verify(requestedIdentity, appSigningInfo)
        assertTrue(resultCt is VerificationResult.Rejected.InvalidContentType)
        assertFalse(resultCt is VerificationResult.Rejected.RequestedPackageMismatch)
    }
}
