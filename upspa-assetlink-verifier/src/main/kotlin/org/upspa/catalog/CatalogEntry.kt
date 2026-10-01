package org.upspa.catalog

import org.upspa.assetlinks.model.EnrolledOrigin

/**
 * An individual entry in the identity catalog representing an enrolled origin,
 * an account reference, security policy, counter, and associated alias evidence.
 *
 * Invariants:
 * - If [aliasEvidence.reference] is non-null, [aliasEvidence.reference.sourceOrigin] must exactly
 *   equal [enrolledOrigin] (scheme, host, port).
 * - If [aliasEvidence.status] is [AliasEvidenceStatus.VERIFIED], [enrolledOrigin] must be HTTPS.
 * - [derivationIdentity] produces the canonical derivation string `enrolledOrigin + "|" + accountReference.value`.
 * - [toString] is redacted and strictly excludes the sensitive account reference.
 */
data class CatalogEntry(
    val enrolledOrigin: EnrolledOrigin,
    val accountReference: AccountReference,
    val policy: NormalizedPasswordPolicy,
    val encoderCounter: EncoderCounter,
    val compatibilityProfileVersion: CompatibilityProfileVersion,
    val aliasEvidence: AliasEvidence
) {
    init {
        val ref = aliasEvidence.reference
        if (ref != null) {
            require(ref.sourceOrigin == enrolledOrigin) {
                "AliasEvidenceReference sourceOrigin must exactly match catalog entry enrolledOrigin"
            }
        }
        if (aliasEvidence.status == AliasEvidenceStatus.VERIFIED) {
            require(enrolledOrigin.isHttps) {
                "EnrolledOrigin must be HTTPS for VERIFIED alias evidence"
            }
        }
    }

    /**
     * Derives the login-server identifier string: `enrolledOrigin + "|" + accountReference.value`.
     *
     * SENSITIVE: Never log, trace, or expose this value in unencrypted diagnostics.
     */
    fun derivationIdentity(): String = "${enrolledOrigin.toOriginString()}|${accountReference.value}"

    override fun toString(): String {
        return "CatalogEntry(enrolledOrigin=<redacted>, accountReference=<redacted>, " +
                "compatibilityProfileVersion=${compatibilityProfileVersion.value}, " +
                "encoderCounter=${encoderCounter.value}, policy=$policy, aliasEvidence=$aliasEvidence)"
    }
}
