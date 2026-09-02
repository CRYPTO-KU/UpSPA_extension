package org.upspa.assetlinks.model

import org.upspa.assetlinks.crypto.CertificateUtils
import java.security.cert.X509Certificate

/**
 * Strongly-typed value representation of a normalized SHA-256 certificate digest (§1.3, §1.4).
 *
 * Guaranteed to be formatted as standard uppercase colon-separated hex:
 * `14:6D:E9:A1:B2:C3:...:17` (95 characters, 32 bytes).
 */
@JvmInline
value class CertificateDigest(val value: String) {

    init {
        require(CertificateUtils.isValidSha256Fingerprint(value)) {
            "Invalid SHA-256 certificate digest: '$value'. Must be a 32-byte hex digest."
        }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Parses and normalizes a hex string (with or without colons/spaces) into a [CertificateDigest].
         */
        @JvmStatic
        fun fromHex(rawFingerprint: String): CertificateDigest {
            val normalized = CertificateUtils.normalizeFingerprint(rawFingerprint)
            return CertificateDigest(normalized)
        }

        /**
         * Safely parses and normalizes a hex string into a [CertificateDigest], returning null on failure.
         */
        @JvmStatic
        fun fromHexOrNull(rawFingerprint: String): CertificateDigest? {
            val normalized = CertificateUtils.normalizeFingerprintOrNull(rawFingerprint) ?: return null
            return CertificateDigest(normalized)
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
