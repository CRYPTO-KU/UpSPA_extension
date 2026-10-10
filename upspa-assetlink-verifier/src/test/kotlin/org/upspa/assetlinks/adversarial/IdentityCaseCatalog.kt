package org.upspa.assetlinks.adversarial

import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.AssetLinkEvidence
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.model.Target
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.AssetLinkVerifier
import kotlin.reflect.KClass

enum class Category {
    EXACT_MATCH,
    PACKAGE_MISMATCH,
    CERTIFICATE_MISMATCH,
    ORIGIN_BOUNDARY,
    MALFORMED_CERTIFICATE,
    CONFLICTING_EVIDENCE,
    MULTIPLE_SIGNERS,
    ROTATION,
    REQUESTED_ENROLLED_BINDING
}

sealed interface Expected {
    data class Verified(
        val assertFields: ((VerificationResult.Verified) -> Unit)? = null
    ) : Expected

    data class Rejected(
        val expectedClass: KClass<out VerificationResult.Rejected>,
        val assertFields: ((VerificationResult.Rejected) -> Unit)? = null
    ) : Expected

    data class ConstructionRejected(
        val expectedException: KClass<out Throwable> = IllegalArgumentException::class
    ) : Expected
}

data class CaseInput(
    val requestedOrigin: Origin,
    val appSigningInfo: AppSigningInfo,
    val evidence: AssetLinkEvidence
)

data class IdentityCase(
    val id: String,
    val category: Category,
    val description: String,
    val build: () -> CaseInput,
    val expected: Expected,
    val bothEntryPoints: Boolean = true
)

object IdentityCaseCatalog {

    const val CERT_A = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"
    const val CERT_B = "24:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:27"
    const val CERT_C = "34:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:37"
    const val CERT_ONE_BYTE_DIFF = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:18"
    const val CERT_PREFIX_MATCH = "14:6D:E9:A1:B2:C3:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE:EE"

    val PKG_STANDARD = AndroidPackageName.of("com.example.app")

    val ORIGIN_HTTPS = Origin.parse("https://example.com")
    val ORIGIN_HTTPS_PORT = Origin.parse("https://example.com:8443")
    val ORIGIN_SUBDOMAIN = Origin.parse("https://sub.example.com")
    val ORIGIN_AUTH_SUBDOMAIN = Origin.parse("https://auth.example.com")
    val ORIGIN_APP_SUBDOMAIN = Origin.parse("https://app.example.com")
    val ORIGIN_EVIL = Origin.parse("https://evil-example.com")
    val ORIGIN_EXAMPLE_EVIL = Origin.parse("https://example.com.evil.com")

    private fun standardStatement(
        pkg: String = PKG_STANDARD.value,
        fingerprints: List<String> = listOf(CERT_A),
        relation: String = AssetLinkVerifier.DEFAULT_RELATION,
        namespace: String = "android_app"
    ): AssetLinkStatement {
        return AssetLinkStatement(
            relation = listOf(relation),
            target = Target(
                namespace = namespace,
                packageName = pkg,
                sha256CertFingerprints = fingerprints
            )
        )
    }

    private fun standardEvidence(
        origin: Origin = ORIGIN_HTTPS,
        statements: List<AssetLinkStatement> = listOf(standardStatement())
    ): AssetLinkEvidence = AssetLinkEvidence(sourceOrigin = origin, statements = statements)

    private fun standardApp(
        pkg: AndroidPackageName = PKG_STANDARD,
        certDigest: String = CERT_A
    ): AppSigningInfo = AppSigningInfo.fromFingerprints(pkg.value, listOf(certDigest))

    val ALL_CASES: List<IdentityCase> = listOf(
        // -------------------------------------------------------------
        // 1. EXACT_MATCH (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "EXACT_MATCH_SINGLE_SIGNER",
            category = Category.EXACT_MATCH,
            description = "Standard single signer exactly matching statement verifies",
            build = {
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.Verified { res ->
                require(res.packageName == PKG_STANDARD.value)
                require(res.origin == ORIGIN_HTTPS)
            }
        ),
        IdentityCase(
            id = "EXACT_MATCH_UPPERCASE_HOST_NORMALIZES",
            category = Category.EXACT_MATCH,
            description = "Uppercase host input normalizes to lowercase canonical host and verifies",
            build = {
                val origin = Origin.parse("HTTPS://EXAMPLE.COM")
                CaseInput(origin, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Verified()
        ),
        IdentityCase(
            id = "EXACT_MATCH_NON_DEFAULT_PORT",
            category = Category.EXACT_MATCH,
            description = "Non-default port verifies against evidence bound to the same port",
            build = {
                CaseInput(
                    ORIGIN_HTTPS_PORT,
                    standardApp(),
                    standardEvidence(ORIGIN_HTTPS_PORT, listOf(standardStatement()))
                )
            },
            expected = Expected.Verified()
        ),
        IdentityCase(
            id = "EXACT_MATCH_DEFAULT_PORT_EQUIVALENCE",
            category = Category.EXACT_MATCH,
            description = "Origin with explicit default port 443 normalizes to standard origin and verifies",
            build = {
                val origin = Origin.parse("https://example.com:443")
                CaseInput(origin, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Verified()
        ),

        // -------------------------------------------------------------
        // 2. PACKAGE_MISMATCH (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "PACKAGE_MISMATCH_DIFFERENT",
            category = Category.PACKAGE_MISMATCH,
            description = "Different package name in statement yields PackageNotFound",
            build = {
                CaseInput(
                    ORIGIN_HTTPS,
                    standardApp(),
                    standardEvidence(statements = listOf(standardStatement(pkg = "com.other.app")))
                )
            },
            expected = Expected.Rejected(VerificationResult.Rejected.PackageNotFound::class)
        ),
        IdentityCase(
            id = "PACKAGE_MISMATCH_CASE_DIFFERENCE",
            category = Category.PACKAGE_MISMATCH,
            description = "Case difference in package name (Com.Example.App) rejected",
            build = {
                val app = AppSigningInfo.fromFingerprints("Com.Example.App", listOf(CERT_A))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.PackageNotFound::class)
        ),
        IdentityCase(
            id = "PACKAGE_MISMATCH_PREFIX_LOOKALIKE",
            category = Category.PACKAGE_MISMATCH,
            description = "Prefix lookalike package evil.com.example.app rejected",
            build = {
                val app = AppSigningInfo.fromFingerprints("evil.com.example.app", listOf(CERT_A))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.PackageNotFound::class)
        ),
        IdentityCase(
            id = "PACKAGE_MISMATCH_SUFFIX_LOOKALIKE",
            category = Category.PACKAGE_MISMATCH,
            description = "Suffix lookalike package com.example.app.evil rejected",
            build = {
                val app = AppSigningInfo.fromFingerprints("com.example.app.evil", listOf(CERT_A))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.PackageNotFound::class)
        ),
        IdentityCase(
            id = "PACKAGE_MISMATCH_NON_ANDROID_NAMESPACE",
            category = Category.PACKAGE_MISMATCH,
            description = "Statement with web namespace rejected with PackageNotFound",
            build = {
                CaseInput(
                    ORIGIN_HTTPS,
                    standardApp(),
                    standardEvidence(statements = listOf(standardStatement(namespace = "web")))
                )
            },
            expected = Expected.Rejected(VerificationResult.Rejected.PackageNotFound::class)
        ),
        IdentityCase(
            id = "PACKAGE_MISMATCH_MALFORMED_NAME",
            category = Category.PACKAGE_MISMATCH,
            description = "Syntactically invalid package name rejected with InvalidPackageName",
            build = {
                val app = AppSigningInfo.fromFingerprints("com.example..bad", listOf(CERT_A))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.InvalidPackageName::class)
        ),

        // -------------------------------------------------------------
        // 3. CERTIFICATE_MISMATCH (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "CERTIFICATE_MISMATCH_DIFFERENT_DIGEST",
            category = Category.CERTIFICATE_MISMATCH,
            description = "Claimed cert B vs statement authorizing cert A yields CertificateMismatch",
            build = {
                val app = AppSigningInfo.fromFingerprints(PKG_STANDARD.value, listOf(CERT_B))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.CertificateMismatch::class)
        ),
        IdentityCase(
            id = "CERTIFICATE_MISMATCH_ONE_BYTE_DIFFERENCE",
            category = Category.CERTIFICATE_MISMATCH,
            description = "Claimed cert differing by exactly one byte (:18 vs :17) yields CertificateMismatch",
            build = {
                val app = AppSigningInfo.fromFingerprints(PKG_STANDARD.value, listOf(CERT_ONE_BYTE_DIFF))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.CertificateMismatch::class)
        ),
        IdentityCase(
            id = "CERTIFICATE_MISMATCH_PREFIX_DIGEST",
            category = Category.CERTIFICATE_MISMATCH,
            description = "Claimed cert sharing prefix with authorized cert yields CertificateMismatch (proves M4)",
            build = {
                val app = AppSigningInfo.fromFingerprints(PKG_STANDARD.value, listOf(CERT_PREFIX_MATCH))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.CertificateMismatch::class)
        ),
        IdentityCase(
            id = "CERTIFICATE_MISMATCH_AUTHORIZED_FOR_ANOTHER_PACKAGE",
            category = Category.CERTIFICATE_MISMATCH,
            description = "Cert authorized in statement for another package only yields CertificateMismatch",
            build = {
                val statements = listOf(
                    standardStatement(pkg = PKG_STANDARD.value, fingerprints = listOf(CERT_B)),
                    standardStatement(pkg = "com.other.app", fingerprints = listOf(CERT_A))
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(statements = statements))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.CertificateMismatch::class)
        ),

        // -------------------------------------------------------------
        // 4. ORIGIN_BOUNDARY (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "ORIGIN_BOUNDARY_SUBDOMAIN",
            category = Category.ORIGIN_BOUNDARY,
            description = "Subdomain origin cannot claim evidence bound to apex domain",
            build = {
                CaseInput(ORIGIN_SUBDOMAIN, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_PARENT_DOMAIN",
            category = Category.ORIGIN_BOUNDARY,
            description = "Apex domain cannot claim evidence bound to subdomain",
            build = {
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(ORIGIN_SUBDOMAIN))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_SIBLING_SUBDOMAIN",
            category = Category.ORIGIN_BOUNDARY,
            description = "Sibling subdomain cannot claim evidence bound to another subdomain",
            build = {
                CaseInput(ORIGIN_AUTH_SUBDOMAIN, standardApp(), standardEvidence(ORIGIN_APP_SUBDOMAIN))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_LOOKALIKE_EVIL_EXAMPLE",
            category = Category.ORIGIN_BOUNDARY,
            description = "evil-example.com cannot claim evidence bound to example.com",
            build = {
                CaseInput(ORIGIN_EVIL, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_LOOKALIKE_EXAMPLE_EVIL",
            category = Category.ORIGIN_BOUNDARY,
            description = "example.com.evil.com cannot claim evidence bound to example.com",
            build = {
                CaseInput(ORIGIN_EXAMPLE_EVIL, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_DIFFERENT_PORT",
            category = Category.ORIGIN_BOUNDARY,
            description = "Port 8443 cannot claim evidence bound to port 443",
            build = {
                CaseInput(ORIGIN_HTTPS_PORT, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_NON_HTTPS_REJECTION",
            category = Category.ORIGIN_BOUNDARY,
            description = "Insecure http scheme rejected fail-closed with NonHttpsOrigin",
            build = {
                val httpOrigin = Origin.parse("http://example.com")
                CaseInput(httpOrigin, standardApp(), standardEvidence(httpOrigin))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.NonHttpsOrigin::class)
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_HOST_TRAILING_DOT",
            category = Category.ORIGIN_BOUNDARY,
            description = "Host with trailing dot is rejected in Origin construction",
            build = {
                Origin.parse("https://example.com.")
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_HOST_PATH_CHARS",
            category = Category.ORIGIN_BOUNDARY,
            description = "Host with unbracketed colon/path rejected in Origin direct construction",
            build = {
                Origin("https", "example.com/path", 443)
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "ORIGIN_BOUNDARY_HOST_NON_ASCII",
            category = Category.ORIGIN_BOUNDARY,
            description = "Non-ASCII host rejected in Origin direct construction",
            build = {
                Origin("https", "exämple.com", 443)
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),

        // -------------------------------------------------------------
        // 5. MALFORMED_CERTIFICATE (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "MALFORMED_CERT_RAW_CORRUPT",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Corrupt raw certificate bytes fail closed in Pure verifier validateCertificates",
            build = {
                val corruptBytes = byteArrayOf(0x30, 0x05, 0x01, 0x02)
                val app = AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    signingCertificates = listOf(corruptBytes)
                )
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateEvidence::class)
        ),
        IdentityCase(
            id = "MALFORMED_CERT_TRAILING_BYTES",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Valid certificate with appended trailing garbage fails CertificateUtils parser",
            build = {
                val validCertBytes = CertificateUtils.sampleX509CertificateBytes
                val certWithTrailing = validCertBytes + byteArrayOf(0x00, 0x01, 0x02)
                val app = AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    signingCertificates = listOf(certWithTrailing)
                )
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateEvidence::class) { rej ->
                require(rej.reason.contains("corrupted or unparseable raw certificate bytes")) {
                    "Rejection did not originate from raw certificate validation check: ${rej.reason}"
                }
            }
        ),
        IdentityCase(
            id = "MALFORMED_CERT_CLAIMED_FINGERPRINT",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Malformed claimed fingerprint string yields MalformedCertificateEvidence",
            build = {
                val app = AppSigningInfo.fromFingerprints(PKG_STANDARD.value, listOf("NOT_HEX_DIGEST"))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateEvidence::class)
        ),
        IdentityCase(
            id = "MALFORMED_CERT_STATEMENT_TARGET_FINGERPRINT",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Malformed fingerprint in statement target yields MalformedCertificateFingerprint",
            build = {
                val stmt = standardStatement(fingerprints = listOf("NOT_A_VALID_FINGERPRINT"))
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(statements = listOf(stmt)))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateFingerprint::class)
        ),
        IdentityCase(
            id = "MALFORMED_CERT_MIXED_VALID_AND_MALFORMED",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Statement with one valid and one malformed fingerprint fails whole evidence closed",
            build = {
                val stmt = standardStatement(fingerprints = listOf(CERT_A, "CORRUPT_HEX"))
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(statements = listOf(stmt)))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateFingerprint::class)
        ),
        IdentityCase(
            id = "MALFORMED_CERT_EMPTY_FINGERPRINT",
            category = Category.MALFORMED_CERTIFICATE,
            description = "Empty fingerprint in statement target yields MalformedCertificateFingerprint",
            build = {
                val stmt = standardStatement(fingerprints = listOf(""))
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(statements = listOf(stmt)))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MalformedCertificateFingerprint::class)
        ),

        // -------------------------------------------------------------
        // 6. CONFLICTING_EVIDENCE (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "CONFLICTING_EVIDENCE_TYPED_CURRENT_PLUS_RAW_CURRENT",
            category = Category.CONFLICTING_EVIDENCE,
            description = "Supplying both typed current and raw current certs is rejected in AppSigningInfo",
            build = {
                val validCertBytes = CertificateUtils.sampleX509CertificateBytes
                AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    currentFingerprints = setOf(CertificateDigest.fromHex(CERT_A)),
                    signingCertificates = listOf(validCertBytes)
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "CONFLICTING_EVIDENCE_TYPED_HISTORY_PLUS_RAW_CURRENT",
            category = Category.CONFLICTING_EVIDENCE,
            description = "Supplying typed history plus raw current is rejected in AppSigningInfo",
            build = {
                val validCertBytes = CertificateUtils.sampleX509CertificateBytes
                AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    rotationHistory = listOf(CertificateDigest.fromHex(CERT_A)),
                    signingCertificates = listOf(validCertBytes)
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "CONFLICTING_EVIDENCE_RAW_STRING_PLUS_RAW_BYTES",
            category = Category.CONFLICTING_EVIDENCE,
            description = "Supplying both raw strings and raw bytes families is rejected",
            build = {
                val validCertBytes = CertificateUtils.sampleX509CertificateBytes
                AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    rawCurrentFingerprints = listOf(CERT_A),
                    signingCertificates = listOf(validCertBytes)
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "CONFLICTING_EVIDENCE_NO_FAMILY_SUPPLIED",
            category = Category.CONFLICTING_EVIDENCE,
            description = "Supplying no certificate family in AppSigningInfo is rejected",
            build = {
                AppSigningInfo(
                    packageName = PKG_STANDARD.value
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),
        IdentityCase(
            id = "CONFLICTING_EVIDENCE_CURRENT_NOT_IN_ROTATION_HISTORY",
            category = Category.CONFLICTING_EVIDENCE,
            description = "Current signer missing from rotation lineage is rejected",
            build = {
                AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    currentFingerprints = setOf(CertificateDigest.fromHex(CERT_C)),
                    rotationHistory = listOf(
                        CertificateDigest.fromHex(CERT_A),
                        CertificateDigest.fromHex(CERT_B)
                    )
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),

        // -------------------------------------------------------------
        // 7. MULTIPLE_SIGNERS (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "MULTIPLE_SIGNERS_ONE_AUTHORIZED",
            category = Category.MULTIPLE_SIGNERS,
            description = "App with two signers where one is authorized rejected with MultipleSignersUnsupported",
            build = {
                val app = AppSigningInfo.fromMultiSigners(PKG_STANDARD.value, listOf(CERT_A, CERT_B))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence())
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MultipleSignersUnsupported::class)
        ),
        IdentityCase(
            id = "MULTIPLE_SIGNERS_BOTH_AUTHORIZED",
            category = Category.MULTIPLE_SIGNERS,
            description = "App with two signers where both are authorized rejected with MultipleSignersUnsupported",
            build = {
                val app = AppSigningInfo.fromMultiSigners(PKG_STANDARD.value, listOf(CERT_A, CERT_B))
                val stmts = listOf(
                    standardStatement(fingerprints = listOf(CERT_A, CERT_B))
                )
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MultipleSignersUnsupported::class)
        ),
        IdentityCase(
            id = "MULTIPLE_SIGNERS_COMBINED_WITH_ROTATION",
            category = Category.MULTIPLE_SIGNERS,
            description = "Multi-signer combined with rotation history is rejected in AppSigningInfo",
            build = {
                AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    currentFingerprints = setOf(
                        CertificateDigest.fromHex(CERT_A),
                        CertificateDigest.fromHex(CERT_B)
                    ),
                    rotationHistory = listOf(CertificateDigest.fromHex(CERT_A))
                )
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence())
            },
            expected = Expected.ConstructionRejected()
        ),

        // -------------------------------------------------------------
        // 8. ROTATION (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "ROTATION_VALID_LINEAGE_MATCHES_NEW",
            category = Category.ROTATION,
            description = "Valid lineage [old, new], current new, statement authorizes new -> Verified",
            build = {
                val app = AppSigningInfo.fromRotationHistory(PKG_STANDARD.value, listOf(CERT_A, CERT_B))
                val stmts = listOf(standardStatement(fingerprints = listOf(CERT_B)))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Verified { res ->
                require(res.matchedFingerprint == CERT_B)
            }
        ),
        IdentityCase(
            id = "ROTATION_LINEAGE_MATCHES_OLD",
            category = Category.ROTATION,
            description = "OBSERVED CURRENT BEHAVIOR: An assetlinks statement listing only an older lineage key authorizes the rotated app.",
            build = {
                val app = AppSigningInfo.fromRotationHistory(PKG_STANDARD.value, listOf(CERT_A, CERT_B))
                val stmts = listOf(standardStatement(fingerprints = listOf(CERT_A)))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Verified { res ->
                require(res.matchedFingerprint == CERT_A)
            }
        ),
        IdentityCase(
            id = "ROTATION_LINEAGE_MATCHES_NEITHER",
            category = Category.ROTATION,
            description = "Lineage [certA, certB] vs statement authorizing certC -> CertificateMismatch",
            build = {
                val app = AppSigningInfo.fromRotationHistory(PKG_STANDARD.value, listOf(CERT_A, CERT_B))
                val stmts = listOf(standardStatement(fingerprints = listOf(CERT_C)))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.CertificateMismatch::class)
        ),
        IdentityCase(
            id = "ROTATION_CURRENT_NOT_LAST_IN_HISTORY",
            category = Category.ROTATION,
            description = "OBSERVED CURRENT BEHAVIOR: A lineage whose declared current signer is not the newest entry still verifies.",
            build = {
                val app = AppSigningInfo(
                    packageName = PKG_STANDARD.value,
                    currentFingerprints = setOf(CertificateDigest.fromHex(CERT_A)),
                    rotationHistory = listOf(
                        CertificateDigest.fromHex(CERT_A),
                        CertificateDigest.fromHex(CERT_B)
                    )
                )
                val stmts = listOf(standardStatement(fingerprints = listOf(CERT_A)))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Verified { res ->
                require(res.matchedFingerprint == CERT_A)
            }
        ),
        IdentityCase(
            id = "ROTATION_HISTORY_LONGER_THAN_TWO",
            category = Category.ROTATION,
            description = "Lineage with 3 keys [certA, certB, certC] authorizes certC and verifies",
            build = {
                val app = AppSigningInfo.fromRotationHistory(PKG_STANDARD.value, listOf(CERT_A, CERT_B, CERT_C))
                val stmts = listOf(standardStatement(fingerprints = listOf(CERT_C)))
                CaseInput(ORIGIN_HTTPS, app, standardEvidence(statements = stmts))
            },
            expected = Expected.Verified { res ->
                require(res.matchedFingerprint == CERT_C)
            }
        ),

        // -------------------------------------------------------------
        // 9. REQUESTED_ENROLLED_BINDING (>= 3)
        // -------------------------------------------------------------
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_MATCHES",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence sourceOrigin exactly matches requestedOrigin -> Verified",
            build = {
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Verified()
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_REPLAY_EVIL",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence from example.com replayed for evil-example.com -> OriginMismatch",
            build = {
                CaseInput(ORIGIN_EVIL, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_REPLAY_SUBDOMAIN",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence from example.com replayed for sub.example.com -> OriginMismatch",
            build = {
                CaseInput(ORIGIN_SUBDOMAIN, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_REPLAY_PARENT",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence from sub.example.com replayed for example.com -> OriginMismatch",
            build = {
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(ORIGIN_SUBDOMAIN))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_PORT",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence from example.com:443 replayed for example.com:8443 -> OriginMismatch",
            build = {
                CaseInput(ORIGIN_HTTPS_PORT, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_REPLAY_OTHER_SCHEME",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Evidence from https://example.com replayed for requested origin http://example.com -> OriginMismatch (origin binding check fires before HTTPS enforcement)",
            build = {
                val httpOrigin = Origin.parse("http://example.com")
                CaseInput(httpOrigin, standardApp(), standardEvidence(ORIGIN_HTTPS))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.OriginMismatch::class),
            bothEntryPoints = false
        ),
        IdentityCase(
            id = "REQUESTED_ENROLLED_BINDING_MISSING_RELATION",
            category = Category.REQUESTED_ENROLLED_BINDING,
            description = "Statement grants custom relation but request requires handle_all_urls",
            build = {
                val stmt = standardStatement(relation = "delegate_permission/custom.relation")
                CaseInput(ORIGIN_HTTPS, standardApp(), standardEvidence(statements = listOf(stmt)))
            },
            expected = Expected.Rejected(VerificationResult.Rejected.MissingRequiredRelation::class)
        )
    )
}
