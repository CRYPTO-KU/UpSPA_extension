package org.upspa.assetlinks.verifier

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.upspa.assetlinks.crypto.CertificateUtils
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult

/**
 * Pure, deterministic, network-isolated Digital Asset Links verification engine (§1.4, §1.5).
 *
 * Guarantees:
 * - **Zero Network I/O**: Operates purely on pre-fetched/injected statement evidence in-memory.
 * - **Fail-Closed Security**: Any ambiguity, malformed evidence, or mismatch produces an explicit typed rejection.
 * - **Strict HTTPS Enforcement**: Rejects non-HTTPS schemes by default (§1.2).
 * - **Exact Identity Matching**: Strictly exact, case-sensitive matching on package name (no prefix/suffix/substring matching).
 * - **Rotation Lineage Support**: Validates single certs, multiple historical certs (APK v3 key rotation),
 *   while rejecting unsupported multi-signer states (§7.2, §10.3).
 */
class PureAssetLinkVerifier(
    private val allowInsecureHttp: Boolean = false
) {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val statementsAdapter: JsonAdapter<List<AssetLinkStatement>> =
        moshi.adapter(Types.newParameterizedType(List::class.java, AssetLinkStatement::class.java))

    /**
     * Verifies relationship from raw JSON statement evidence.
     */
    fun verifyRawJson(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        rawJson: String,
        requiredRelation: String? = null
    ): VerificationResult {
        val statements = try {
            statementsAdapter.fromJson(rawJson)
                ?: return VerificationResult.Rejected.InvalidJsonFormat("assetlinks.json evaluated to null")
        } catch (e: JsonDataException) {
            return VerificationResult.Rejected.InvalidJsonFormat("JSON syntax error: ${e.message}")
        } catch (e: Exception) {
            return VerificationResult.Rejected.InvalidJsonFormat("Failed to parse assetlinks.json: ${e.message}")
        }

        return verifyStatements(origin, appSigningInfo, statements, requiredRelation)
    }

    /**
     * Purely verifies relationship directly from structured statements evidence.
     */
    fun verifyStatements(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        statements: List<AssetLinkStatement>,
        requiredRelation: String? = null
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

        val claimedFingerprints = appSigningInfo.getAllSha256Fingerprints()
        if (claimedFingerprints.isEmpty()) {
            return VerificationResult.Rejected.NoSigningCertificatesFound(appSigningInfo.packageName)
        }

        val normalizedClaimedFingerprints = claimedFingerprints.mapNotNull { CertificateUtils.normalizeFingerprintOrNull(it) }
        if (normalizedClaimedFingerprints.isEmpty()) {
            return VerificationResult.Rejected.NoSigningCertificatesFound(appSigningInfo.packageName)
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

        // Step 4: Validate certificates and relations across matching statements
        val authorizedFingerprints = mutableListOf<String>()
        val availableRelations = mutableListOf<String>()
        var certificateMatchedAnyStatement = false

        for (statement in matchingPackageStatements) {
            val statementRelations = statement.relation
            availableRelations.addAll(statementRelations)

            val targetFingerprints = statement.target?.sha256CertFingerprints.orEmpty()
            for (fp in targetFingerprints) {
                if (!CertificateUtils.isValidSha256Fingerprint(fp)) {
                    return VerificationResult.Rejected.MalformedCertificateFingerprint(fp)
                }

                val normalizedFp = CertificateUtils.normalizeFingerprintOrNull(fp) ?: continue
                authorizedFingerprints.add(normalizedFp)

                // Check certificate match against claimed certificates (including key rotation history)
                if (normalizedClaimedFingerprints.contains(normalizedFp)) {
                    certificateMatchedAnyStatement = true

                    // Check required relation
                    if (requiredRelation == null || statementRelations.contains(requiredRelation)) {
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
        if (certificateMatchedAnyStatement && requiredRelation != null) {
            return VerificationResult.Rejected.MissingRequiredRelation(
                packageName = appSigningInfo.packageName,
                requiredRelation = requiredRelation,
                availableRelations = availableRelations.distinct()
            )
        }

        // Otherwise, certificate did not match authorized statement certificates
        val primaryClaimed = normalizedClaimedFingerprints.firstOrNull() ?: claimedFingerprints.first()
        return VerificationResult.Rejected.CertificateMismatch(
            packageName = appSigningInfo.packageName,
            claimedFingerprint = primaryClaimed,
            statementFingerprints = authorizedFingerprints.distinct(),
            allClaimedFingerprints = normalizedClaimedFingerprints
        )
    }
}
