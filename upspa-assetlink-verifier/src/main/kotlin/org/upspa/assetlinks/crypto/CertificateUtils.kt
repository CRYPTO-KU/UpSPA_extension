package org.upspa.assetlinks.crypto

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale

/**
 * Cryptographic certificate utilities for Digital Asset Links SHA-256 fingerprint generation
 * and format normalization (§1.3).
 *
 * Official AssetLink fingerprint format: uppercase, colon-separated hex (e.g. "14:6D:E9:...:FC:F4:4E:05").
 */
object CertificateUtils {

    private const val SHA256_ALGORITHM = "SHA-256"

    const val SAMPLE_X509_CERT_BASE64 =
        "MIICzzCCAbegAwIBAgIIFNvu1j4yS8EwDQYJKoZIhvcNAQELBQAwFjEUMBIGA1UE" +
        "AxMLZXhhbXBsZS5jb20wHhcNMjYwOTA1MTQwNDExWhcNMjcwOTA1MTQwNDExWjAW" +
        "MRQwEgYDVQQDEwtleGFtcGxlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCC" +
        "AQoCggEBALFXFZokSVwpSIWYkrvH6CBMKvNws374lkEILlfeYxmnnRWcmomY3MJd" +
        "QiRtyNJHjz0xU7FjMI5g5iGCbbt1VnHz0GuZVRfI1MtFuPyrg/Y6+289x2R3UfH1" +
        "8CWuzOz7sU6K3LQr1KVLPhQlM+FF6Ef3/9tvrcbQJGia5LXEtA+8oskXGjgIFie0" +
        "mPcyR7ycsf2RkXDsMZm5yaXl6UjeFV4SzNNpAVEX86pXswna9H5BeG6R3E9oHAEE" +
        "XJPSP2tmHazeGsp5Z93oFt9hQKjD++lLQFoQ0FU/8XpuEJMXUZfuWK02h/y2+CF3" +
        "QNkc0AG4sJrTfFpUuTAfjnLpiLXTOgkCAwEAAaMhMB8wHQYDVR0OBBYEFCNmmaLi" +
        "8u6/yMT2Pegxwio6oJBrMA0GCSqGSIb3DQEBCwUAA4IBAQBGfBgdGJPqtyPHK7ad" +
        "T7OK/k0IbZAAX48Ze2nTOUjQtWIEbExLdy+Hv9u5fR9TFdyhbxgOM0X/rimZTqKd" +
        "QqyU0/jm7Rx6C0k/fQwXukZanZtj+odr5pLwkgA86wqoF41+OTw5yzT/Jhm28y92" +
        "ZmxrQiIF9Rp/TzymJYkTWGgawCoGwc5X0+277GEgDOdSTaeyRt193dKRubQt7/pz" +
        "RP4d4aSH/1DZVN/HExE6obNZjMdJVlZTUIPrJHowVoJd13yKQBRUgp+GpG7WuRAg" +
        "5tBhZpgIrgqD0S52CkAZKylGKvoTdOhwkrblFZxwvltTxspys3pvOlLUKFu4OKcX" +
        "T7Cv"

    @JvmStatic
    val sampleX509CertificateBytes: ByteArray by lazy {
        Base64.getDecoder().decode(SAMPLE_X509_CERT_BASE64)
    }

    /**
     * Validates whether [certificateBytes] forms a syntactically valid DER or PEM encoded X.509 certificate.
     */
    @JvmStatic
    fun isValidX509Certificate(certificateBytes: ByteArray): Boolean {
        if (certificateBytes.isEmpty()) return false
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            factory.generateCertificate(ByteArrayInputStream(certificateBytes)) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Computes the SHA-256 digest of the given binary certificate/signature bytes
     * and formats it as an uppercase, colon-separated hex string.
     */
    @JvmStatic
    fun computeSha256Fingerprint(certificateBytes: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val hash = digest.digest(certificateBytes)
        return formatAsColonSeparatedHex(hash)
    }

    /**
     * Safely computes the SHA-256 fingerprint, or returns null on error.
     */
    @JvmStatic
    fun computeSha256FingerprintOrNull(certificateBytes: ByteArray): String? {
        return try {
            if (certificateBytes.isEmpty()) null else computeSha256Fingerprint(certificateBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Computes the SHA-256 fingerprint from an [X509Certificate].
     */
    @JvmStatic
    fun computeSha256Fingerprint(certificate: X509Certificate): String {
        return computeSha256Fingerprint(certificate.encoded)
    }

    /**
     * Checks whether a raw fingerprint string is a valid SHA-256 digest representation.
     */
    @JvmStatic
    fun isValidSha256Fingerprint(rawFingerprint: String): Boolean {
        val cleanHex = rawFingerprint.replace(":", "").replace(" ", "").replace("-", "")
        if (cleanHex.length != 64) return false
        return cleanHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Normalizes any SHA-256 hex string representation (with or without colons, mixed case)
     * into standard Digital Asset Links uppercase colon-separated format.
     *
     * Example input: "146de9a1..." -> "14:6D:E9:A1:..."
     */
    @JvmStatic
    fun normalizeFingerprint(rawFingerprint: String): String {
        val cleanHex = rawFingerprint.replace(":", "").replace(" ", "").replace("-", "")
        require(cleanHex.length == 64 && cleanHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid SHA-256 fingerprint (expected 64 hex chars): '$rawFingerprint'"
        }
        return cleanHex.chunked(2).joinToString(":") { it.uppercase(Locale.US) }
    }

    /**
     * Safely normalizes fingerprint or returns null if invalid.
     */
    @JvmStatic
    fun normalizeFingerprintOrNull(rawFingerprint: String): String? {
        return try {
            normalizeFingerprint(rawFingerprint)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Formats raw byte array into colon-separated uppercase hex format.
     */
    @JvmStatic
    fun formatAsColonSeparatedHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 16) sb.append('0')
            sb.append(Integer.toHexString(b).uppercase(Locale.US))
            if (i < bytes.size - 1) {
                sb.append(':')
            }
        }
        return sb.toString()
    }

    /**
     * Converts a colon-separated or plain hex string into a raw byte array.
     */
    @JvmStatic
    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(":", "").replace(" ", "").replace("-", "")
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            val v = clean.substring(index, index + 2).toInt(16)
            result[i] = v.toByte()
        }
        return result
    }
}
