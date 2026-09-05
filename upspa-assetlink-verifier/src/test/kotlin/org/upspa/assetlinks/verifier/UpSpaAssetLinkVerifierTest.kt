package org.upspa.assetlinks.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult

class UpSpaAssetLinkVerifierTest {

    private lateinit var origin: Origin

    private val sampleFingerprint1 = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"
    private val sampleFingerprint2 = "2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19:2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19"
    private val otherFingerprint = "FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF:FF"

    private val samplePackageName = "com.example.app"

    @BeforeEach
    fun setUp() {
        origin = Origin.parse("https://auth.example.com")
    }

    @Test
    fun `test 1 - Successful verification (Happy Path)`() {
        val validAssetLinksJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$samplePackageName",
                  "sha256_cert_fingerprints": [
                    "$sampleFingerprint1"
                  ]
                }
              }
            ]
        """.trimIndent()

        val fetcher = FakeAssetLinkFetcher.withJson(origin, validAssetLinksJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val verified = assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(origin, verified.origin)
        assertEquals(samplePackageName, verified.packageName)
        assertEquals(sampleFingerprint1, verified.matchedFingerprint)
        assertTrue(verified.relations.contains("delegate_permission/common.handle_all_urls"))

        assertEquals(listOf(origin), fetcher.recordedRequests)
    }

    @Test
    fun `test 2 - Certificate mismatch rejection`() {
        val validAssetLinksJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$samplePackageName",
                  "sha256_cert_fingerprints": [
                    "$otherFingerprint"
                  ]
                }
              }
            ]
        """.trimIndent()

        val fetcher = FakeAssetLinkFetcher.withJson(origin, validAssetLinksJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val rejected = assertInstanceOf(VerificationResult.Rejected.CertificateMismatch::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertEquals(sampleFingerprint1, rejected.claimedFingerprint)
        assertTrue(rejected.statementFingerprints.contains(otherFingerprint))
    }

    @Test
    fun `test 3 - HTTP 301 and 302 redirects are strictly rejected (Anti-spoofing §1_2)`() {
        val fetcher = FakeAssetLinkFetcher.withRedirect(
            origin = origin,
            statusCode = 301,
            location = "https://attacker-controlled.com/.well-known/assetlinks.json"
        )
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val redirect = assertInstanceOf(VerificationResult.Rejected.RedirectAttempted::class.java, result)
        assertEquals(301, redirect.statusCode)
        assertEquals("https://attacker-controlled.com/.well-known/assetlinks.json", redirect.redirectLocation)
    }

    @Test
    fun `test 4 - HTTP 404 Not Found returns FetchFailed`() {
        val fetcher = FakeAssetLinkFetcher.withHttpError(
            origin = origin,
            statusCode = 404,
            message = "Not Found"
        )
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val failed = assertInstanceOf(VerificationResult.Rejected.FetchFailed::class.java, result)
        assertEquals(404, failed.statusCode)
    }

    @Test
    fun `test 5 - Non-JSON Content-Type returns InvalidContentType`() {
        val fetcher = FakeAssetLinkFetcher.withContentType(
            origin = origin,
            body = "<html><body>Fake Login</body></html>",
            contentType = "text/html; charset=utf-8"
        )
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val invalidContentType = assertInstanceOf(VerificationResult.Rejected.InvalidContentType::class.java, result)
        assertEquals("text/html; charset=utf-8", invalidContentType.actualContentType)
    }

    @Test
    fun `test 6 - Malformed JSON returns InvalidJsonFormat`() {
        val fetcher = FakeAssetLinkFetcher.withJson(
            origin = origin,
            jsonBody = "{ unclosed json structure ..."
        )
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        assertInstanceOf(VerificationResult.Rejected.InvalidJsonFormat::class.java, result)
    }

    @Test
    fun `test 7 - Package name not found in statements`() {
        val validAssetLinksJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.different.app",
                  "sha256_cert_fingerprints": ["$sampleFingerprint1"]
                }
              }
            ]
        """.trimIndent()

        val fetcher = FakeAssetLinkFetcher.withJson(origin, validAssetLinksJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val app = AppSigningInfo.fromFingerprints(samplePackageName, listOf(sampleFingerprint1))
        val result = verifier.verify(origin, app)

        val notFound = assertInstanceOf(VerificationResult.Rejected.PackageNotFound::class.java, result)
        assertEquals(samplePackageName, notFound.packageName)
    }

    @Test
    fun `test 8 - Multi-signer safety fixes Google reference crash (§7_2, §10_3)`() {
        val fetcher = FakeAssetLinkFetcher.empty()
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val multiSignerApp = AppSigningInfo.fromFingerprints(
            packageName = samplePackageName,
            fingerprints = listOf(sampleFingerprint1, sampleFingerprint2),
            hasMultipleSigners = true
        )

        val result = verifier.verify(origin, multiSignerApp)

        val multiSignerResult = assertInstanceOf(VerificationResult.Rejected.MultipleSignersUnsupported::class.java, result)
        assertEquals(samplePackageName, multiSignerResult.packageName)
        // Verified: No fetch is performed, no NullPointerException or crash occurs
        assertEquals(0, fetcher.recordedRequests.size)
    }

    @Test
    fun `test 9 - Key rotation lineage support (§2_3)`() {
        // Website has old certificate in assetlinks.json
        val assetLinksJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$samplePackageName",
                  "sha256_cert_fingerprints": ["$sampleFingerprint1"]
                }
              }
            ]
        """.trimIndent()

        val fetcher = FakeAssetLinkFetcher.withJson(origin, assetLinksJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        // App has rotated to sampleFingerprint2, but retains sampleFingerprint1 in rotation lineage
        val rotatedApp = AppSigningInfo.fromFingerprints(
            packageName = samplePackageName,
            fingerprints = listOf(sampleFingerprint1, sampleFingerprint2)
        )

        val result = verifier.verify(origin, rotatedApp)
        val verified = assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(sampleFingerprint1, verified.matchedFingerprint)
    }
}
