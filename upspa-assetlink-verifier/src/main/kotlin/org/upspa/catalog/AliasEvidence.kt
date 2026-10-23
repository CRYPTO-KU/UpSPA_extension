package org.upspa.catalog

import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.EnrolledOrigin

/**
 * Status of alias verification evidence in catalog entries.
 */
enum class AliasEvidenceStatus {
    VERIFIED,
    UNVERIFIED,
    PENDING,
    MANUAL
}

/**
 * Reason codes when [AliasEvidenceStatus] is [AliasEvidenceStatus.UNVERIFIED].
 */
enum class UnverifiedReason {
    EVIDENCE_MISSING,
    EVIDENCE_STALE,
    EVIDENCE_UNAVAILABLE,
    VERIFICATION_REJECTED
}

/**
 * Reference pointer to verified alias assertion.
 * Contains only package name, signing certificate digest, and source origin.
 * Never contains rotation history or raw certificate bytes.
 */
data class AliasEvidenceReference(
    val packageName: AndroidPackageName,
    val signingCertificateDigest: CertificateDigest,
    val sourceOrigin: EnrolledOrigin
) {
    override fun toString(): String {
        return "AliasEvidenceReference(packageName=$packageName, signingCertificateDigest=$signingCertificateDigest, sourceOrigin=<redacted>)"
    }
}

/**
 * Alias evidence structure associated with a catalog entry.
 *
 * Cross-field rules enforced in [init]:
 * - `VERIFIED`: [reference] required; [unverifiedReason] must be null
 * - `UNVERIFIED`: [unverifiedReason] required; [reference] optional
 * - `PENDING`: [unverifiedReason] must be null; [reference] optional
 * - `MANUAL`: [reference] must be null; [unverifiedReason] must be null
 */
data class AliasEvidence(
    val status: AliasEvidenceStatus,
    val reference: AliasEvidenceReference? = null,
    val unverifiedReason: UnverifiedReason? = null
) {
    init {
        when (status) {
            AliasEvidenceStatus.VERIFIED -> {
                require(reference != null) { "AliasEvidence with status VERIFIED requires non-null reference" }
                require(unverifiedReason == null) { "AliasEvidence with status VERIFIED must have null unverifiedReason" }
            }
            AliasEvidenceStatus.UNVERIFIED -> {
                require(unverifiedReason != null) { "AliasEvidence with status UNVERIFIED requires non-null unverifiedReason" }
            }
            AliasEvidenceStatus.PENDING -> {
                require(unverifiedReason == null) { "AliasEvidence with status PENDING must have null unverifiedReason" }
            }
            AliasEvidenceStatus.MANUAL -> {
                require(reference == null) { "AliasEvidence with status MANUAL must have null reference" }
                require(unverifiedReason == null) { "AliasEvidence with status MANUAL must have null unverifiedReason" }
            }
        }
    }

    /**
     * Indicates whether this catalog evidence is eligible for credential release.
     *
     * IMPORTANT: This condition is necessary but NOT sufficient for releasing credentials.
     * The consumer layer MUST perform fresh, independent origin verification at request time
     * against live platform evidence matching this reference before releasing credentials.
     * [AliasEvidenceStatus.MANUAL] is permanently ineligible.
     */
    val isEligibleForCredentialRelease: Boolean = (status == AliasEvidenceStatus.VERIFIED)
}
