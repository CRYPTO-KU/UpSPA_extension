package org.upspa.assetlinks.result

import org.upspa.assetlinks.model.Origin

/**
 * Result hierarchy for Digital Asset Links verification operations (§1.5, §7.2, §10.3).
 *
 * Replaces Google's boolean/exception-prone implementation with an exhaustive,
 * strongly-typed sealed class hierarchy guaranteeing zero unhandled crashes.
 */
sealed interface VerificationResult {

    /**
     * Successfully verified relationship between the Web Origin and the Android App.
     *
     * @property origin The verified web origin (exact scheme, host, and port).
     * @property packageName The verified Android application package name.
     * @property matchedFingerprint The specific SHA-256 certificate fingerprint that matched.
     * @property relations The list of permission relations asserted by the origin (e.g. `delegate_permission/common.handle_all_urls`).
     */
    data class Verified(
        val origin: Origin,
        val packageName: String,
        val matchedFingerprint: String,
        val relations: List<String>
    ) : VerificationResult

    /**
     * Verification rejected. Contains detailed structural and diagnostic reason
     * for rejection according to the Warning and Rejection Policy.
     */
    sealed interface Rejected : VerificationResult {
        val reason: String

        /**
         * HTTP 301/302/307/308 redirect attempt was detected and strictly blocked (§1.2).
         * Digital Asset Links specification strictly forbids redirects as an anti-spoofing defense.
         */
        data class RedirectAttempted(
            val statusCode: Int,
            val redirectLocation: String?,
            override val reason: String = "HTTP redirect (Status $statusCode to '${redirectLocation ?: "unknown"}') is strictly rejected to prevent domain-spoofing attacks (§1.2)."
        ) : Rejected

        /**
         * Server responded with an HTTP status code other than 200 OK (e.g., 404 Not Found, 500 Server Error).
         */
        data class FetchFailed(
            val statusCode: Int,
            override val reason: String = "Failed to fetch assetlinks.json: Server responded with HTTP status $statusCode."
        ) : Rejected

        /**
         * Network communication or TLS handshake failure.
         */
        data class NetworkError(
            val throwable: Throwable,
            override val reason: String = "Network communication error: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
        ) : Rejected

        /**
         * Server responded with a Content-Type header that is not 'application/json' (§1.2).
         */
        data class InvalidContentType(
            val actualContentType: String?,
            override val reason: String = "Invalid Content-Type header: Expected 'application/json', got '$actualContentType' (§1.2)."
        ) : Rejected

        /**
         * The retrieved assetlinks.json contains malformed or invalid JSON syntax.
         */
        data class InvalidJsonFormat(
            val details: String,
            override val reason: String = "Malformed assetlinks.json format: $details"
        ) : Rejected

        /**
         * The assetlinks.json file exists and is valid, but contains no statements
         * for the claimed Android package name.
         */
        data class PackageNotFound(
            val packageName: String,
            override val reason: String = "Package '$packageName' is not declared in the origin's assetlinks.json statements."
        ) : Rejected

        /**
         * The package name was found, but none of its SHA-256 certificate fingerprints
         * matched the claimed app's signing certificate (§1.4, §2.1).
         */
        data class CertificateMismatch(
            val packageName: String,
            val claimedFingerprint: String,
            val statementFingerprints: List<String>,
            val allClaimedFingerprints: List<String> = listOf(claimedFingerprint),
            override val reason: String = "Certificate fingerprint mismatch for package '$packageName'. Claimed '$claimedFingerprint', but origin only authorizes: $statementFingerprints."
        ) : Rejected

        /**
         * Target application has multiple signers which is unsupported/ambiguous for DAL verification (§7.2, §10.3).
         * Fixes Google's non-null assertion crash on multi-signer packages.
         */
        data class MultipleSignersUnsupported(
            val packageName: String,
            override val reason: String = "Package '$packageName' has multiple APK signers, which is not supported by Digital Asset Links verification (§7.2)."
        ) : Rejected

        /**
         * No signing certificates could be extracted from the target application signing info.
         */
        data class NoSigningCertificatesFound(
            val packageName: String,
            override val reason: String = "No valid signing certificates found for package '$packageName'."
        ) : Rejected

        /**
         * Origin format was invalid or uses an unauthorized protocol (e.g. file://).
         */
        data class InvalidOrigin(
            val rawOrigin: String,
            override val reason: String = "Invalid or unsupported web origin: '$rawOrigin'. Only HTTP/HTTPS exact origins are permitted."
        ) : Rejected

        /**
         * Web origin uses an insecure scheme (e.g. HTTP, FTP). Digital Asset Links specification
         * strictly mandates HTTPS for secure web origins in production (§1.2).
         */
        data class NonHttpsOrigin(
            val rawOrigin: String,
            override val reason: String = "Insecure scheme rejected for origin '$rawOrigin'. Digital Asset Links strictly requires secure HTTPS origins (§1.2)."
        ) : Rejected

        /**
         * The statement list defines permissions for the package and certificate, but
         * does not grant the specifically requested permission relation (§1.5).
         */
        data class MissingRequiredRelation(
            val packageName: String,
            val requiredRelation: String,
            val availableRelations: List<String>,
            override val reason: String = "Required relation '$requiredRelation' is not granted to package '$packageName'. Available relations: $availableRelations."
        ) : Rejected

        /**
         * Claimed or statement package name is malformed or invalid according to Android specifications.
         */
        data class InvalidPackageName(
            val rawPackageName: String,
            override val reason: String = "Invalid or malformed package name: '$rawPackageName'."
        ) : Rejected

        /**
         * A certificate fingerprint in the claimed app evidence or statement list is not a valid 32-byte SHA-256 digest.
         * Fails closed immediately without lossy transformation (§1.4, §1.5).
         */
        data class MalformedCertificateEvidence(
            val rawFingerprint: String? = null,
            override val reason: String = if (rawFingerprint != null) {
                "Malformed certificate fingerprint in evidence: '$rawFingerprint'."
            } else {
                "Malformed certificate evidence encountered."
            }
        ) : Rejected {
            constructor(reason: String) : this(null, reason)
        }

        /**
         * A certificate fingerprint in the statement list is not a valid 32-byte SHA-256 digest.
         */
        data class MalformedCertificateFingerprint(
            val rawFingerprint: String,
            override val reason: String = "Malformed certificate fingerprint in assetlinks statement: '$rawFingerprint'."
        ) : Rejected

        /**
         * Requested origin does not match the authoritative origin bound to the evidence.
         * Mitigates cross-origin statement injection and replay attacks (§1.2, §1.4).
         */
        data class OriginMismatch(
            val requestedOrigin: Origin,
            val evidenceOrigin: Origin,
            override val reason: String = "Requested origin '$requestedOrigin' does not match evidence origin '$evidenceOrigin'."
        ) : Rejected
    }
}
