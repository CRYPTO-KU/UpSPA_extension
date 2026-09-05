package org.upspa.assetlinks.verifier

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.AssetLinkEvidence
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult

/**
 * Pure, deterministic, network-isolated Digital Asset Links verification engine (§1.4, §1.5).
 *
 * Guarantees:
 * - **Zero Network I/O**: Operates purely on pre-fetched/injected statement evidence in-memory.
 * - **Origin Binding**: Prevents cross-origin replay attacks by enforcing that evidence matches requested origin.
 * - **Fail-Closed Security**: Any ambiguity, malformed evidence, or mismatch produces an explicit typed rejection.
 * - **Strict HTTPS Enforcement**: Rejects non-HTTPS schemes by default (§1.2).
 * - **Exact Identity Matching**: Strictly exact, case-sensitive matching on package name (no prefix/suffix/substring matching).
 * - **Rotation Lineage Support**: Validates single certs, multiple historical certs (APK v3 key rotation),
 *   while rejecting unsupported multi-signer states (§7.2, §10.3).
 */
class PureAssetLinkVerifier internal constructor(
    private val allowInsecureHttp: Boolean
) {
    constructor() : this(allowInsecureHttp = false)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val statementsAdapter: JsonAdapter<List<AssetLinkStatement>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, AssetLinkStatement::class.java))

    /**
     * Primary verification entry point enforcing origin-bound evidence matching (§1.2, §1.4).
     *
     * Validates that [evidence] was explicitly issued by or intended for [requestedOrigin]
     * to prevent cross-origin replay and injection attacks.
     */
    fun verify(
        requestedOrigin: Origin,
        appSigningInfo: AppSigningInfo,
        evidence: AssetLinkEvidence,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult {
        // Enforce exact origin binding
        if (requestedOrigin != evidence.sourceOrigin) {
            return VerificationResult.Rejected.OriginMismatch(
                requestedOrigin = requestedOrigin,
                evidenceOrigin = evidence.sourceOrigin
            )
        }

        return verifyStatementsInternal(
            origin = requestedOrigin,
            appSigningInfo = appSigningInfo,
            statements = evidence.statements,
            requiredRelation = requiredRelation
        )
    }

    /**
     * Verifies relationship from raw JSON statement evidence.
     *
     * Parses the JSON and wraps it into an [AssetLinkEvidence] bound to [origin].
     */
    fun verifyRawJson(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        rawJson: String,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult {
        val statements = try {
            statementsAdapter.fromJson(rawJson)
                ?: return VerificationResult.Rejected.InvalidJsonFormat("assetlinks.json evaluated to null")
        } catch (e: JsonDataException) {
            return VerificationResult.Rejected.InvalidJsonFormat("JSON syntax error: ${e.message}")
        } catch (e: Exception) {
            return VerificationResult.Rejected.InvalidJsonFormat("Failed to parse assetlinks.json: ${e.message}")
        }

        val evidence = AssetLinkEvidence(sourceOrigin = origin, statements = statements)
        return verify(
            requestedOrigin = origin,
            appSigningInfo = appSigningInfo,
            evidence = evidence,
            requiredRelation = requiredRelation
        )
    }

    /**
     * Legacy verification entry point directly taking bare statements list.
     *
     * Wraps statements into an [AssetLinkEvidence] bound to [origin] and delegates to [verify].
     */
    @Deprecated(
        message = "Use verify(requestedOrigin, appSigningInfo, evidence, requiredRelation) to enforce origin binding",
        replaceWith = ReplaceWith("verify(origin, appSigningInfo, AssetLinkEvidence(origin, statements), requiredRelation)")
    )
    fun verifyStatements(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        statements: List<AssetLinkStatement>,
        requiredRelation: String = AssetLinkVerifier.DEFAULT_RELATION
    ): VerificationResult {
        return verify(
            requestedOrigin = origin,
            appSigningInfo = appSigningInfo,
            evidence = AssetLinkEvidence(sourceOrigin = origin, statements = statements),
            requiredRelation = requiredRelation
        )
    }

    private fun verifyStatementsInternal(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        statements: List<AssetLinkStatement>,
        requiredRelation: String
    ): VerificationResult {
        // Step 0: Enforce secure HTTPS origin (§1.2)
        if (!origin.isHttps && !allowInsecureHttp) {
            return VerificationResult.Rejected.NonHttpsOrigin(origin.toOriginString())
        }

        // Step 1: Validate claimed package syntax (§1.4)
        if (!AndroidPackageName.isValid(appSigningInfo.packageName)) {
            return VerificationResult.Rejected.InvalidPackageName(appSigningInfo.packageName)
        }

        // Step 2: Multi-signer safety (§7.2, §10.3)
        if (appSigningInfo.hasMultipleSigners) {
            return VerificationResult.Rejected.MultipleSignersUnsupported(appSigningInfo.packageName)
        }

        // Fail-closed on corrupted or unparseable raw certificates
        if (!appSigningInfo.validateCertificates()) {
            return VerificationResult.Rejected.MalformedCertificateEvidence(
                reason = "AppSigningInfo contains corrupted or unparseable raw certificate bytes for package '${appSigningInfo.packageName}'."
            )
        }

        val claimedFingerprints = appSigningInfo.getAllSha256Fingerprints()
        if (claimedFingerprints.isEmpty()) {
            return VerificationResult.Rejected.NoSigningCertificatesFound(appSigningInfo.packageName)
        }

        // Strictly inspect all claimed certificate digests fail-closed without lossy transform
        val normalizedClaimedFingerprints = mutableListOf<String>()
        for (fp in claimedFingerprints) {
            val normalized = CertificateUtils.normalizeFingerprintOrNull(fp)
                ?: return VerificationResult.Rejected.MalformedCertificateEvidence(
                    rawFingerprint = fp,
                    reason = "Malformed certificate fingerprint in claimed evidence: '$fp'."
                )
            normalizedClaimedFingerprints.add(normalized)
        }

        // Step 3: Exact match filtering for statements targeting android_app with matching package_name (§1.4)
        val matchingPackageStatements = statements.filter { statement ->
            val target = statement.target
            target != null &&
                    target.namespace.equals("android_app", ignoreCase = true) &&
                    target.packageName == appSigningInfo.packageName // STRICT EXACT CASE-SENSITIVE EQUALITY
        }

        if (matchingPackageStatements.isEmpty()) {
            return VerificationResult.Rejected.PackageNotFound(appSigningInfo.packageName)
        }

        // Pre-validate all target fingerprints across matching statements before matching
        for (statement in matchingPackageStatements) {
            for (fp in statement.target?.sha256CertFingerprints.orEmpty()) {
                if (!CertificateUtils.isValidSha256Fingerprint(fp)) {
                    return VerificationResult.Rejected.MalformedCertificateFingerprint(fp)
                }
            }
        }

        // Step 4: Validate certificates and relations across matching statements
        val authorizedFingerprints = mutableListOf<String>()
        val availableRelations = mutableListOf<String>()
        var certificateMatchedAnyStatement = false

        for (statement in matchingPackageStatements) {
            val statementRelations = statement.relation
            availableRelations.addAll(statementRelations)

            val targetFingerprints = statement.target?.sha256CertFingerprints.orEmpty()
            for (fp in targetFingerprints) {
                val normalizedFp = CertificateUtils.normalizeFingerprintOrNull(fp) ?: continue
                authorizedFingerprints.add(normalizedFp)

                // Check certificate match against claimed certificates (including key rotation history)
                if (normalizedClaimedFingerprints.contains(normalizedFp)) {
                    certificateMatchedAnyStatement = true

                    // Check required relation
                    if (statementRelations.contains(requiredRelation)) {
                        return VerificationResult.Verified(
                            origin = origin,
                            packageName = appSigningInfo.packageName,
                            matchedFingerprint = normalizedFp,
                            relations = statementRelations
                        )
                    }
                }
            }
        }

        // If certificate matched but required relation was not satisfied -> fail-closed with MissingRequiredRelation
        if (certificateMatchedAnyStatement) {
            return VerificationResult.Rejected.MissingRequiredRelation(
                packageName = appSigningInfo.packageName,
                requiredRelation = requiredRelation,
                availableRelations = availableRelations.distinct()
            )
        }

        // Otherwise, certificate did not match authorized statement certificates
        val primaryClaimed = normalizedClaimedFingerprints.first()
        return VerificationResult.Rejected.CertificateMismatch(
            packageName = appSigningInfo.packageName,
            claimedFingerprint = primaryClaimed,
            statementFingerprints = authorizedFingerprints.distinct(),
            allClaimedFingerprints = normalizedClaimedFingerprints
        )
    }
}
