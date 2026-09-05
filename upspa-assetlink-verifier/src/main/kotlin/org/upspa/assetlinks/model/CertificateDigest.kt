package org.upspa.assetlinks.model

import org.upspa.assetlinks.crypto.CertificateUtils
import java.security.cert.X509Certificate

/**
 * Strongly-typed value representation of a normalized SHA-256 certificate digest (§1.3, §1.4).
 *
 * Guaranteed to be formatted as standard uppercase colon-separated hex:
 * `14:6D:E9:A1:B2:C3:...:17` (95 characters, 32 bytes).
 */
class CertificateDigest(rawDigest: String) {

    val value: String = CertificateUtils.normalizeFingerprint(rawDigest)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertificateDigest) return false
        return value == other.value
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        /**
         * Parses and normalizes a hex string (with or without colons/spaces) into a [CertificateDigest].
         */
        @JvmStatic
        fun fromHex(rawFingerprint: String): CertificateDigest = CertificateDigest(rawFingerprint)

        /**
         * Safely parses and normalizes a hex string into a [CertificateDigest], returning null on failure.
         */
        @JvmStatic
        fun fromHexOrNull(rawFingerprint: String): CertificateDigest? {
            return try {
                CertificateDigest(rawFingerprint)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Computes SHA-256 digest from raw binary certificate bytes.
         */
        @JvmStatic
        fun fromCertificateBytes(certificateBytes: ByteArray): CertificateDigest {
            val fp = CertificateUtils.computeSha256Fingerprint(certificateBytes)
            return CertificateDigest(fp)
        }

        /**
         * Computes SHA-256 digest from an [X509Certificate].
         */
        @JvmStatic
        fun fromX509Certificate(certificate: X509Certificate): CertificateDigest {
            return fromCertificateBytes(certificate.encoded)
        }
    }
}
