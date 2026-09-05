package org.upspa.assetlinks.verifier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.AssetLinkEvidence
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.CanonicalWebOrigin
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.model.Target
import org.upspa.assetlinks.result.VerificationResult

/**
 * Adversarial Negative Control Test Suite proving fail-closed security guarantees.
 *
 * Each test case corresponds directly to a required security invariant:
 * 1. Package name mismatch
 * 2. Certificate mismatch
 * 3. Malformed origin
 * 4. Non-HTTPS origin
 * 5. Suffix/substring confusion (e.g. `evil-example.com` vs `example.com`)
 * 6. Redirect handling
 * 7. Missing relation / statement not present for target
 * 8. Multiple signers
 * 9. Signing-key rotation history
 */
class AdversarialNegativeControlTest {

    private val origin = Origin.parse("https://auth.example.com")
    private val samplePackageName = "com.example.app"

    private val certA = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"
    private val certB = "2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19:2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19"
    private val certEvil = "EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE"

    private fun createStatementJson(
        packageName: String,
        fingerprints: List<String>,
        relations: List<String> = listOf("delegate_permission/common.handle_all_urls")
    ): String {
        val certsJson = fingerprints.joinToString(", ") { "\"$it\"" }
        val relationsJson = relations.joinToString(", ") { "\"$it\"" }
        return """
            [
              {
                "relation": [$relationsJson],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$packageName",
                  "sha256_cert_fingerprints": [$certsJson]
                }
              }
            ]
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 1: Package Name Mismatch
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 1 - Package name mismatch fails closed with PackageNotFound`() {
        val statementJson = createStatementJson("com.different.app", listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))
        val result = verifier.verify(origin, claimedApp)

        val rejected = assertInstanceOf(VerificationResult.Rejected.PackageNotFound::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertTrue(rejected.reason.contains("not declared in the origin's assetlinks.json"))
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 2: Certificate Mismatch
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 2 - Certificate mismatch fails closed with CertificateMismatch`() {
        // Origin authorizes certA, but claimed app signs with certEvil
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certEvil))
        val result = verifier.verify(origin, claimedApp)

        val rejected = assertInstanceOf(VerificationResult.Rejected.CertificateMismatch::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertEquals(certEvil, rejected.claimedFingerprint)
        assertTrue(rejected.statementFingerprints.contains(certA))
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 3: Malformed Origin
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 3 - Malformed origin fails closed with InvalidOrigin`() {
        val verifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.empty())
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val malformedOrigins = listOf(
            "not a valid url",
            "https://",
            "https://::invalid-port",
            "file:///etc/hosts",
            "javascript:void(0)"
        )

        for (badOrigin in malformedOrigins) {
            val result = verifier.verify(badOrigin, claimedApp)
            val rejected = assertInstanceOf(
                VerificationResult.Rejected.InvalidOrigin::class.java,
                result,
                "Expected InvalidOrigin for '$badOrigin'"
            )
            assertEquals(badOrigin, rejected.rawOrigin)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 4: Non-HTTPS Origin
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 4 - Non-HTTPS origin fails closed with NonHttpsOrigin`() {
        val fetcher = FakeAssetLinkFetcher.empty()
        // Default allowInsecureHttp = false strictly mandates HTTPS
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher, allowInsecureHttp = false)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val insecureOrigin = Origin.parse("http://auth.example.com")
        val result = verifier.verify(insecureOrigin, claimedApp)

        val rejected = assertInstanceOf(VerificationResult.Rejected.NonHttpsOrigin::class.java, result)
        assertEquals("http://auth.example.com", rejected.rawOrigin)
        // Verified: Zero network fetch attempts occurred
        assertTrue(fetcher.recordedRequests.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 5: Suffix and Substring Confusion (No Fuzzy Matching)
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 5 - Suffix and substring confusion fails closed (no fuzzy match)`() {
        // Substring attack on package name:
        // Statement targets "com.example.app.evil" or "evil-com.example.app"
        // Attacker attempts to match "com.example.app"
        val statementJson = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example.app.evil",
                  "sha256_cert_fingerprints": ["$certA"]
                }
              },
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "evil-com.example.app",
                  "sha256_cert_fingerprints": ["$certA"]
                }
              }
            ]
        """.trimIndent()

        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val result = verifier.verify(origin, claimedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.PackageNotFound::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)

        // Substring attack on Origin:
        // Origin "https://evil-example.com" or "https://auth.example.com.evil.com"
        val evilOrigin = Origin.parse("https://evil-example.com")
        val evilFetcher = FakeAssetLinkFetcher.empty()
        val verifierForEvil = UpSpaAssetLinkVerifier(fetcher = evilFetcher)

        val evilResult = verifierForEvil.verify(evilOrigin, claimedApp)
        // Because evilFetcher has no statement for evilOrigin, it fails closed with HTTP 404
        assertInstanceOf(VerificationResult.Rejected.FetchFailed::class.java, evilResult)
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 6: Redirect Handling
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 6 - Redirect attempt strictly fails closed with RedirectAttempted`() {
        val fetcher = FakeAssetLinkFetcher.withRedirect(
            origin = origin,
            statusCode = 301,
            location = "https://hijacked.attacker.com/.well-known/assetlinks.json"
        )
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val result = verifier.verify(origin, claimedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.RedirectAttempted::class.java, result)
        assertEquals(301, rejected.statusCode)
        assertEquals("https://hijacked.attacker.com/.well-known/assetlinks.json", rejected.redirectLocation)
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 7: Missing Relation
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 7 - Missing required relation fails closed with MissingRequiredRelation`() {
        // Statement authorizes credentials, but verification requires handle_all_urls
        val statementJson = createStatementJson(
            packageName = samplePackageName,
            fingerprints = listOf(certA),
            relations = listOf("delegate_permission/common.get_login_creds")
        )
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val result = verifier.verify(
            origin = origin,
            appSigningInfo = claimedApp,
            requiredRelation = "delegate_permission/common.handle_all_urls"
        )

        val rejected = assertInstanceOf(VerificationResult.Rejected.MissingRequiredRelation::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertEquals("delegate_permission/common.handle_all_urls", rejected.requiredRelation)
        assertTrue(rejected.availableRelations.contains("delegate_permission/common.get_login_creds"))
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 8: Multiple Current Signers
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 8 - Multiple current signers fails closed with MultipleSignersUnsupported`() {
        val statementJson = createStatementJson(samplePackageName, listOf(certA, certB))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val multiSignerApp = AppSigningInfo.fromMultiSigners(
            packageName = samplePackageName,
            fingerprints = listOf(certA, certB)
        )

        val result = verifier.verify(origin, multiSignerApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.MultipleSignersUnsupported::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        // Zero fetch attempts because failure is fail-closed in pure verifier
        assertTrue(fetcher.recordedRequests.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Negative Control 9: Signing-Key Rotation History Mismatch
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `negative control 9 - Signing-key rotation history mismatch fails closed`() {
        // Origin authorizes only certEvil
        val statementJson = createStatementJson(samplePackageName, listOf(certEvil))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        // App has rotation lineage certA -> certB, neither of which matches certEvil
        val rotatedApp = AppSigningInfo.fromRotationHistory(
            packageName = samplePackageName,
            historyFingerprints = listOf(certA, certB)
        )

        val result = verifier.verify(origin, rotatedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.CertificateMismatch::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertTrue(rejected.statementFingerprints.contains(certEvil))
    }

    // ---------------------------------------------------------------------------------------------
    // Positive Controls: Verifying successful cases
    // ---------------------------------------------------------------------------------------------
    @Test
    fun `positive control - Successful verification with single signer`() {
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val result = verifier.verify(origin, claimedApp)
        val verified = assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(origin, verified.origin)
        assertEquals(samplePackageName, verified.packageName)
        assertEquals(certA, verified.matchedFingerprint)
    }

    @Test
    fun `positive control - Successful verification matching historical rotated key`() {
        // Origin authorizes old certificate (certA)
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        // App has rotated to certB, but retains certA in rotation history
        val rotatedApp = AppSigningInfo.fromRotationHistory(
            packageName = samplePackageName,
            historyFingerprints = listOf(certA, certB)
        )

        val result = verifier.verify(origin, rotatedApp)
        val verified = assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(certA, verified.matchedFingerprint)
    }

    // ---------------------------------------------------------------------------------------------
    // PR Review Regression Tests: Fail-Closed Strictness & Value Invariant Guarantees
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `testEvidenceWithOneValidAndOneMalformedFingerprintFailsClosed`() {
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        // Claimed evidence contains 1 valid matching fingerprint (certA) and 1 malformed fingerprint
        val malformedFp = "MALFORMED:HEX:FINGERPRINT:XX"
        val claimedApp = AppSigningInfo.fromFingerprints(
            packageName = samplePackageName,
            fingerprints = listOf(certA, malformedFp)
        )

        val result = verifier.verify(origin, claimedApp)

        // Must strictly fail closed with typed MalformedCertificateEvidence, never Verified
        val rejected = assertInstanceOf(VerificationResult.Rejected.MalformedCertificateEvidence::class.java, result)
        assertTrue(rejected.reason.contains(malformedFp))
    }

    @Test
    fun `testCanonicalValueTypesEqualityAndCaseInsensitivity`() {
        // Origin equality and casing normalization
        val originUpper = Origin("HTTPS", "AUTH.EXAMPLE.COM", 443)
        val originLower = Origin("https", "auth.example.com", 443)
        assertEquals(originUpper, originLower)
        assertEquals(originUpper.hashCode(), originLower.hashCode())
        assertEquals("https", originUpper.scheme)
        assertEquals("auth.example.com", originUpper.host)

        // CertificateDigest equality and casing/formatting normalization
        val digestUpper = CertificateDigest(certA.uppercase())
        val digestLower = CertificateDigest(certA.lowercase())
        val digestNoColons = CertificateDigest(certA.replace(":", "").lowercase())
        val digestSpaces = CertificateDigest(certA.replace(":", " "))
        assertEquals(digestUpper, digestLower)
        assertEquals(digestUpper, digestNoColons)
        assertEquals(digestUpper, digestSpaces)
        assertEquals(digestUpper.hashCode(), digestLower.hashCode())
        assertEquals(digestUpper.hashCode(), digestNoColons.hashCode())
        assertEquals(digestUpper.hashCode(), digestSpaces.hashCode())
        assertEquals(certA.uppercase(), digestUpper.value)
        assertEquals(certA.uppercase(), digestLower.value)

        // CanonicalWebOrigin and EnrolledOrigin equality with differently-cased origins
        val canonicalUpper = CanonicalWebOrigin(originUpper)
        val canonicalLower = CanonicalWebOrigin(originLower)
        assertEquals(canonicalUpper, canonicalLower)
        assertEquals(canonicalUpper.hashCode(), canonicalLower.hashCode())

        // DAL verification succeeds identically with uppercase origin and lowercase fingerprint
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(originLower, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val appWithLower = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA.lowercase()))
        val verified = verifier.verify(originUpper, appWithLower)
        assertInstanceOf(VerificationResult.Verified::class.java, verified)
    }

    @Test
    fun `testEmptyRelationFailsClosedWithMissingRequiredRelation`() {
        val emptyRelationJson = """
            [
              {
                "relation": [],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$samplePackageName",
                  "sha256_cert_fingerprints": ["$certA"]
                }
              }
            ]
        """.trimIndent()
        val fetcher = FakeAssetLinkFetcher.withJson(origin, emptyRelationJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        val result = verifier.verify(origin, claimedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.MissingRequiredRelation::class.java, result)
        assertEquals(samplePackageName, rejected.packageName)
        assertEquals(AssetLinkVerifier.DEFAULT_RELATION, rejected.requiredRelation)
        assertTrue(rejected.availableRelations.isEmpty())
    }

    @Test
    fun `testInsecureHttpRejectedByDefaultInPublicVerifier`() {
        val fetcher = FakeAssetLinkFetcher.empty()
        // Public constructor does not allow allowInsecureHttp parameter; strictly enforces HTTPS
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))
        val insecureOrigin = Origin.parse("http://auth.example.com")

        val result = verifier.verify(insecureOrigin, claimedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.NonHttpsOrigin::class.java, result)
        assertEquals("http://auth.example.com", rejected.rawOrigin)
        assertTrue(fetcher.recordedRequests.isEmpty())
    }

    @Test
    fun `testRawCertificateCorruptionFailsClosed`() {
        val statementJson = createStatementJson(samplePackageName, listOf(certA))
        val fetcher = FakeAssetLinkFetcher.withJson(origin, statementJson)
        val verifier = UpSpaAssetLinkVerifier(fetcher = fetcher)

        val validCert = CertificateUtils.sampleX509CertificateBytes
        val corruptedCert = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

        val claimedApp = AppSigningInfo(
            packageName = samplePackageName,
            signingCertificates = listOf(validCert, corruptedCert)
        )

        val result = verifier.verify(origin, claimedApp)
        val rejected = assertInstanceOf(VerificationResult.Rejected.MalformedCertificateEvidence::class.java, result)
        assertTrue(rejected.reason.contains("corrupted") || rejected.reason.contains("Malformed"))
    }

    @Test
    fun `testCrossOriginEvidenceReplayRejected`() {
        val authorizedOrigin = Origin.parse("https://example.com")
        val attackerOrigin = Origin.parse("https://evil-example.com")

        val statements = listOf(
            AssetLinkStatement(
                relation = listOf(AssetLinkVerifier.DEFAULT_RELATION),
                target = Target(
                    namespace = "android_app",
                    packageName = samplePackageName,
                    sha256CertFingerprints = listOf(certA)
                )
            )
        )

        // Evidence is explicitly bound to authorizedOrigin
        val evidence = AssetLinkEvidence(
            sourceOrigin = authorizedOrigin,
            statements = statements
        )

        val pureVerifier = PureAssetLinkVerifier()
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        // Attacker attempts to verify under attackerOrigin using evidence from authorizedOrigin
        val result = pureVerifier.verify(
            requestedOrigin = attackerOrigin,
            appSigningInfo = claimedApp,
            evidence = evidence
        )

        val rejected = assertInstanceOf(VerificationResult.Rejected.OriginMismatch::class.java, result)
        assertEquals(attackerOrigin, rejected.requestedOrigin)
        assertEquals(authorizedOrigin, rejected.evidenceOrigin)
    }

    @Test
    fun `testSameOriginEvidenceVerified`() {
        val authorizedOrigin = Origin.parse("https://example.com")

        val statements = listOf(
            AssetLinkStatement(
                relation = listOf(AssetLinkVerifier.DEFAULT_RELATION),
                target = Target(
                    namespace = "android_app",
                    packageName = samplePackageName,
                    sha256CertFingerprints = listOf(certA)
                )
            )
        )

        val evidence = AssetLinkEvidence(
            sourceOrigin = authorizedOrigin,
            statements = statements
        )

        val pureVerifier = PureAssetLinkVerifier()
        val claimedApp = AppSigningInfo.fromFingerprints(samplePackageName, listOf(certA))

        // Matching requestedOrigin == evidence.sourceOrigin succeeds
        val result = pureVerifier.verify(
            requestedOrigin = authorizedOrigin,
            appSigningInfo = claimedApp,
            evidence = evidence
        )

        val verified = assertInstanceOf(VerificationResult.Verified::class.java, result)
        assertEquals(authorizedOrigin, verified.origin)
        assertEquals(samplePackageName, verified.packageName)
        assertEquals(certA, verified.matchedFingerprint)
    }
}
