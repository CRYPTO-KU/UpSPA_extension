package org.upspa.assetlinks.model

import org.upspa.assetlinks.crypto.CertificateUtils
import java.security.cert.X509Certificate

/**
 * Encapsulates the package identity and signing certificate information of an Android app.
 *
 * Designed to cleanly mirror Android's `android.content.pm.SigningInfo` and `PackageInfo`
 * while fixing Google's `AssetLinkVerifier.kt` non-null assertion crash on multi-signer apps (§7.2, §10.3).
 *
 * @property packageName The claimed Android application package name (e.g. "com.example.app").
 * @property hasMultipleSigners True if APK signature scheme indicates multiple signers.
 * @property directFingerprints Pre-computed, normalized SHA-256 fingerprints (e.g. from tests or metadata).
 * @property signingCertificates The raw DER-encoded signing certificates of the application.
 * @property signingCertificateHistory The lineage of raw DER-encoded signing certificates (oldest to newest) when APK key rotation (v3) is present.
 */
data class AppSigningInfo(
    val packageName: String,
    val hasMultipleSigners: Boolean = false,
    val directFingerprints: List<String> = emptyList(),
    val signingCertificates: List<ByteArray> = emptyList(),
    val signingCertificateHistory: List<ByteArray> = emptyList()
) {
    init {
        require(packageName.isNotBlank()) { "Package name must not be blank" }
    }

    val packageIdentity: AndroidPackageName
        get() = AndroidPackageName.of(packageName)

    /**
     * Extracts all SHA-256 fingerprints (formatted as uppercase AA:BB:CC:... hex).
     *
     * If [directFingerprints] are provided, returns them directly (preventing double-hashing).
     * Otherwise, computes SHA-256 digests from [signingCertificateHistory] or [signingCertificates].
     */
    fun getAllSha256Fingerprints(): List<String> {
        if (directFingerprints.isNotEmpty()) {
            return directFingerprints
        }
        val certs = if (signingCertificateHistory.isNotEmpty()) {
            signingCertificateHistory
        } else {
            signingCertificates
        }
        return certs.mapNotNull { CertificateUtils.computeSha256FingerprintOrNull(it) }
    }

    /**
     * Extracts all certificates as strongly-typed [CertificateDigest] instances.
     */
    fun getAllCertificateDigests(): List<CertificateDigest> {
        return getAllSha256Fingerprints().mapNotNull { CertificateDigest.fromHexOrNull(it) }
    }

    /**
     * Returns the primary (latest) signing certificate fingerprint, or null if multi-signer / empty.
     */
    fun getLatestSha256FingerprintOrNull(): String? {
        if (hasMultipleSigners) {
            return null
        }
        if (directFingerprints.isNotEmpty()) {
            return directFingerprints.lastOrNull()
        }
        val targetCert = signingCertificateHistory.lastOrNull() ?: signingCertificates.firstOrNull()
        return targetCert?.let { CertificateUtils.computeSha256FingerprintOrNull(it) }
    }

    /**
     * Returns the primary (latest) signing certificate digest, or null if multi-signer / empty.
     */
    fun getLatestCertificateDigestOrNull(): CertificateDigest? {
        return getLatestSha256FingerprintOrNull()?.let { CertificateDigest.fromHexOrNull(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppSigningInfo
        if (packageName != other.packageName) return false
        if (hasMultipleSigners != other.hasMultipleSigners) return false
        if (directFingerprints != other.directFingerprints) return false
        if (signingCertificates.size != other.signingCertificates.size) return false
        if (signingCertificateHistory.size != other.signingCertificateHistory.size) return false

        for (i in signingCertificates.indices) {
            if (!signingCertificates[i].contentEquals(other.signingCertificates[i])) return false
        }
        for (i in signingCertificateHistory.indices) {
            if (!signingCertificateHistory[i].contentEquals(other.signingCertificateHistory[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + hasMultipleSigners.hashCode()
        result = 31 * result + directFingerprints.hashCode()
        result = 31 * result + signingCertificates.fold(0) { acc, bytes -> acc * 31 + bytes.contentHashCode() }
        result = 31 * result + signingCertificateHistory.fold(0) { acc, bytes -> acc * 31 + bytes.contentHashCode() }
        return result
    }

    companion object {
        /**
         * Creates an [AppSigningInfo] from pre-computed uppercase colon-separated SHA-256 fingerprints.
         * Normalizes fingerprints directly and prevents double-hashing.
         */
        @JvmStatic
        fun fromFingerprints(
            packageName: String,
            fingerprints: List<String>,
            hasMultipleSigners: Boolean = false
        ): AppSigningInfo {
            return AppSigningInfo(
                packageName = packageName,
                hasMultipleSigners = hasMultipleSigners,
                directFingerprints = fingerprints.mapNotNull { CertificateUtils.normalizeFingerprintOrNull(it) }
            )
        }

        /**
         * Creates an [AppSigningInfo] from [X509Certificate] instances.
         */
        @JvmStatic
        fun fromX509Certificates(
            packageName: String,
            certificates: List<X509Certificate>,
            hasMultipleSigners: Boolean = false
        ): AppSigningInfo {
            return AppSigningInfo(
                packageName = packageName,
                hasMultipleSigners = hasMultipleSigners,
                signingCertificates = certificates.map { it.encoded }
            )
        }

        /**
         * Creates an [AppSigningInfo] with multiple current APK signers.
         * Enforces fail-closed multi-signer status flag (§7.2, §10.3).
         */
        @JvmStatic
        fun fromMultiSigners(
            packageName: String,
            fingerprints: List<String>
        ): AppSigningInfo {
            return AppSigningInfo(
                packageName = packageName,
                hasMultipleSigners = true,
                directFingerprints = fingerprints.mapNotNull { CertificateUtils.normalizeFingerprintOrNull(it) }
            )
        }

        /**
         * Creates an [AppSigningInfo] representing an application with signing-key rotation history (§2.3).
         */
        @JvmStatic
        fun fromRotationHistory(
            packageName: String,
            historyFingerprints: List<String>
        ): AppSigningInfo {
            return AppSigningInfo(
                packageName = packageName,
                hasMultipleSigners = false,
                directFingerprints = historyFingerprints.mapNotNull { CertificateUtils.normalizeFingerprintOrNull(it) }
            )
        }

        /**
         * Creates an [AppSigningInfo] from strongly-typed [AndroidPackageName] and [CertificateDigest]s.
         */
        @JvmStatic
        fun fromTypedValues(
            packageIdentity: AndroidPackageName,
            currentSigners: List<CertificateDigest>,
            hasMultipleSigners: Boolean = currentSigners.size > 1,
            rotationHistory: List<CertificateDigest> = emptyList()
        ): AppSigningInfo {
            val allDigests = if (rotationHistory.isNotEmpty()) rotationHistory else currentSigners
            return AppSigningInfo(
                packageName = packageIdentity.value,
                hasMultipleSigners = hasMultipleSigners,
                directFingerprints = allDigests.map { it.value }
            )
        }
    }
}
