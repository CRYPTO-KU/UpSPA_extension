package org.upspa.assetlinks.verifier

import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult

/**
 * Interface defining the Digital Asset Links relationship verification engine.
 */
interface AssetLinkVerifier {

    /**
     * Verifies that the specified [origin] authorizes the given [appSigningInfo]
     * to act on its behalf according to Digital Asset Links specification (§1.5).
     *
     * @param origin Validated web origin (scheme + host + port).
     * @param appSigningInfo Claimed Android app package name and certificate signing history.
     * @param requiredRelation Optional relation string (e.g. "delegate_permission/common.handle_all_urls").
     *                         If null, any valid permission relation for the target package is accepted.
     * @return [VerificationResult] exhaustive sealed class representing verification outcome.
     */
    fun verify(
        origin: Origin,
        appSigningInfo: AppSigningInfo,
        requiredRelation: String? = null
    ): VerificationResult

    /**
     * Overload accepting raw origin URL string.
     */
    fun verify(
        rawOriginUrl: String,
        appSigningInfo: AppSigningInfo,
        requiredRelation: String? = null
    ): VerificationResult

    /**
     * Overload accepting strongly-typed [RequestedIdentity].
     */
    fun verify(
        requestedIdentity: org.upspa.assetlinks.model.RequestedIdentity,
        appSigningInfo: AppSigningInfo
    ): VerificationResult = verify(
        requestedIdentity.origin,
        appSigningInfo,
        requestedIdentity.requiredRelation
    )
}
