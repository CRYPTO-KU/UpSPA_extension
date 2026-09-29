package org.upspa.assetlinks.verifier

import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.fetcher.AssetLinkFetcher
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.fetcher.FetchResult
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.AssetLinkEvidence
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.model.RequestedIdentity
import org.upspa.assetlinks.result.VerificationResult

/**
 * Production-grade AssetLink Verifier implementing UpSPA mobile architecture specification
 * (§1.2, §1.3, §1.4, §1.5, §2.3, §7.2, §10.3).
 *
 * Coordinates retrieval of Digital Asset Links evidence through an injected [AssetLinkFetcher]
 * and delegates verification to [PureAssetLinkVerifier].
 *
 * Core Guarantees:
 * 1. Complete network isolation: Zero bundled network client; default fetcher is hermetic [FakeAssetLinkFetcher].
 * 2. Multi-signer crash protection: safely returns [VerificationResult.Rejected.MultipleSignersUnsupported] (§7.2).
 * 3. Enforces non-redirect policy (`followRedirects = false`) to prevent hijacking (§1.2).
 * 4. Enforces strict `Content-Type: application/json` verification (§1.2).
 * 5. Supports APK key rotation lineage (§2.3).
 * 6. Returns comprehensive sealed [VerificationResult] hierarchy with zero unhandled exceptions.
 */
class UpSpaAssetLinkVerifier internal constructor(
    private val fetcher: AssetLinkFetcher,
    private val allowInsecureHttp: Boolean
) : AssetLinkVerifier {

    @JvmOverloads
    constructor(fetcher: AssetLinkFetcher = FakeAssetLinkFetcher.empty()) : this(fetcher, allowInsecureHttp = false)

    private val pureVerifier: PureAssetLinkVerifier = PureAssetLinkVerifier(allowInsecureHttp = allowInsecureHttp)

    override fun verify(
        rawOriginUrl: String,
        appSigningInfo: AppSigningInfo,
        requiredRelation: String
    ): VerificationResult {
        val origin = Origin.parseOrNull(rawOriginUrl)
            ?: return VerificationResult.Rejected.InvalidOrigin(rawOriginUrl)

        return verify(origin, appSigningInfo, requiredRelation)
    }

    override fun verify(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        requiredRelation: String
    ): VerificationResult {
        // Check 1: Enforce secure HTTPS origin before network fetch (§1.2)
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        if (!origin.isHttps && !allowInsecureHttp) {
            return VerificationResult.Rejected.NonHttpsOrigin(origin.toOriginString())
        }

        // Check 2: Multi-signer safety (§7.2, §10.3)
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        if (appSigningInfo.hasMultipleSigners) {
            return VerificationResult.Rejected.MultipleSignersUnsupported(appSigningInfo.packageName)
        }

        // Check 3: Validate claimed package syntax (§1.4)
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        if (!AndroidPackageName.isValid(appSigningInfo.packageName)) {
            return VerificationResult.Rejected.InvalidPackageName(appSigningInfo.packageName)
        }

        // Check 4: Fail-closed on corrupted or unparseable raw certificates
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        if (!appSigningInfo.validateCertificates()) {
            return VerificationResult.Rejected.MalformedCertificateEvidence(
                reason = "AppSigningInfo contains corrupted or unparseable raw certificate bytes for package '${appSigningInfo.packageName}'."
            )
        }

        // Check 5: Claimed fingerprints presence
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        val claimedFingerprints = appSigningInfo.getAllSha256Fingerprints()
        // Defense in depth: structurally unreachable while AppSigningInfo enforces exactly one non-empty representation family
        if (claimedFingerprints.isEmpty()) {
            return VerificationResult.Rejected.NoSigningCertificatesFound(appSigningInfo.packageName)
        }

        // Check 6: Pre-validate all claimed fingerprints strictly fail-closed before any network fetch
        // (Must match the check order in PureAssetLinkVerifier.verifyStatementsInternal)
        for (fp in claimedFingerprints) {
            if (!CertificateUtils.isValidSha256Fingerprint(fp)) {
                return VerificationResult.Rejected.MalformedCertificateEvidence(
                    rawFingerprint = fp,
                    reason = "Malformed certificate fingerprint in claimed evidence: '$fp'."
                )
            }
        }

        // Fetch statement list evidence from principal via fetcher (§1.2, §1.5 Step 1)
        val fetchResult = fetcher.fetchAssetLinks(origin)
        val rawJson = when (fetchResult) {
            is FetchResult.Success -> {
                val isJson = fetchResult.contentType.split(";").firstOrNull()?.trim()?.equals("application/json", ignoreCase = true) == true
                if (!isJson) {
                    return VerificationResult.Rejected.InvalidContentType(fetchResult.contentType)
                }
                fetchResult.jsonBody
            }
            is FetchResult.Redirect -> return VerificationResult.Rejected.RedirectAttempted(
                statusCode = fetchResult.statusCode,
                redirectLocation = fetchResult.location
            )
            is FetchResult.HttpError -> return VerificationResult.Rejected.FetchFailed(
                statusCode = fetchResult.statusCode,
                reason = "Server returned HTTP ${fetchResult.statusCode}: ${fetchResult.message}"
            )
            is FetchResult.InvalidContentType -> return VerificationResult.Rejected.InvalidContentType(
                actualContentType = fetchResult.actualContentType
            )
            is FetchResult.NetworkFailure -> return VerificationResult.Rejected.NetworkError(
                throwable = fetchResult.cause
            )
        }

        // Delegate to pure verification engine for exact matching & cryptographic validation (§1.5 Steps 2-4)
        return pureVerifier.verifyRawJson(
            origin = origin,
            appSigningInfo = appSigningInfo,
            rawJson = rawJson,
            requiredRelation = requiredRelation
        )
    }

    /**
     * Pure in-memory verification bypassing the fetcher layer completely using origin-bound [AssetLinkEvidence].
     */
    fun verifyEvidence(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        evidence: AssetLinkEvidence,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult = pureVerifier.verify(origin, appSigningInfo, evidence, requiredRelation)

    /**
     * Pure in-memory verification bypassing the fetcher layer completely (legacy statements overload).
     */
    @Deprecated(
        message = "Use verifyEvidence with AssetLinkEvidence to enforce origin binding",
        replaceWith = ReplaceWith("verifyEvidence(origin, appSigningInfo, AssetLinkEvidence(origin, statements), requiredRelation)")
    )
    fun verifyEvidence(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        statements: List<AssetLinkStatement>,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult = pureVerifier.verify(
        requestedOrigin = origin,
        appSigningInfo = appSigningInfo,
        evidence = AssetLinkEvidence(sourceOrigin = origin, statements = statements),
        requiredRelation = requiredRelation
    )

    /**
     * Pure in-memory verification with raw JSON string evidence.
     */
    fun verifyEvidence(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        rawJson: String,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult = pureVerifier.verifyRawJson(origin, appSigningInfo, rawJson, requiredRelation)
}
