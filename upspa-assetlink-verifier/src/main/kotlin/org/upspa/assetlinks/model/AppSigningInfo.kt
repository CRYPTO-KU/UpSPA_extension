package org.upspa.assetlinks.model

import org.upspa.assetlinks.crypto.CertificateUtils
import java.security.cert.X509Certificate

/**
 * Encapsulates the package identity and signing certificate information of an Android app.
 *
 * Designed to cleanly mirror Android's `android.content.pm.SigningInfo` and `PackageInfo`
 * while structurally preventing multi-signer verification bypass (§7.2, §10.3).
 *
 * Exactly one authoritative representation family must be provided:
 * 1. Typed: [currentFingerprints], [rotationHistory]
 * 2. Raw string fingerprints: [rawCurrentFingerprints], [rawRotationHistory]
 * 3. Raw DER certificate bytes: [signingCertificates], [signingCertificateHistory]
 *
 * Providing zero families or more than one family is strictly rejected with [IllegalArgumentException].
 *
 * Multi-signer state is structural: `hasMultipleSigners` is derived from the count of
 * current signers (> 1) strictly within the single active family.
 *
 * @property packageName The claimed Android application package name (e.g. "com.example.app").
 * @property currentFingerprints The set of current active signing certificate digests.
 * @property rotationHistory The lineage of signing certificate digests (oldest to newest) when key rotation is present.
 * @property signingCertificates The raw DER-encoded signing certificates of the application.
 * @property signingCertificateHistory The lineage of raw DER-encoded signing certificates when key rotation is present.
 * @property rawCurrentFingerprints Raw string representations of current signers (e.g. for malformed fingerprint test cases).
 * @property rawRotationHistory Raw string representations of rotation history (e.g. for malformed fingerprint test cases).
 */
data class AppSigningInfo(
    val packageName: String,
    val currentFingerprints: Set<CertificateDigest> = emptySet(),
    val rotationHistory: List<CertificateDigest> = emptyList(),
    val signingCertificates: List<ByteArray> = emptyList(),
    val signingCertificateHistory: List<ByteArray> = emptyList(),
    val rawCurrentFingerprints: List<String> = emptyList(),
    val rawRotationHistory: List<String> = emptyList()
) {
    /**
     * Derives whether the application has multiple concurrent APK signers (> 1).
     *
     * Invariant guarantee: Because exactly one representation family is populated,
     * this property is derived strictly from the single active family.
     */
    val hasMultipleSigners: Boolean
        get() = currentFingerprints.size > 1 || rawCurrentFingerprints.size > 1 || signingCertificates.size > 1

    init {
        require(packageName.isNotBlank()) { "Package name must not be blank" }

        val hasTyped = currentFingerprints.isNotEmpty() || rotationHistory.isNotEmpty()
        val hasRawString = rawCurrentFingerprints.isNotEmpty() || rawRotationHistory.isNotEmpty()
        val hasRawBytes = signingCertificates.isNotEmpty() || signingCertificateHistory.isNotEmpty()

        val populatedFamilies = (if (hasTyped) 1 else 0) + (if (hasRawString) 1 else 0) + (if (hasRawBytes) 1 else 0)

        require(populatedFamilies <= 1) {
            "Ambiguous signing evidence: multiple representation families supplied (typed/raw-string/raw-bytes); provide exactly one authoritative representation."
        }
        require(populatedFamilies == 1) {
            "Empty signing evidence: exactly one representation family must be provided."
        }

        val effectiveHistorySize = rotationHistory.size + rawRotationHistory.size
        // Reject ambiguous evidence: multi-signer apps cannot have rotation history
        require(!(hasMultipleSigners && effectiveHistorySize > 0)) {
            "Ambiguous signing evidence: multi-signer application cannot have rotation history"
        }
        require(!(hasMultipleSigners && signingCertificateHistory.isNotEmpty())) {
            "Ambiguous signing evidence: multi-signer application cannot have certificate history"
        }
        val effectiveCurrentSize = currentFingerprints.size + rawCurrentFingerprints.size
        require(!(effectiveCurrentSize == 0 && effectiveHistorySize > 0)) {
            "Ambiguous signing evidence: rotation history provided without current signer"
        }
        if (rotationHistory.isNotEmpty() && currentFingerprints.isNotEmpty()) {
            require(rotationHistory.containsAll(currentFingerprints)) {
                "Ambiguous signing evidence: current signer must be part of rotation history"
            }
        }
        if (rawRotationHistory.isNotEmpty() && rawCurrentFingerprints.isNotEmpty()) {
            require(rawRotationHistory.containsAll(rawCurrentFingerprints)) {
                "Ambiguous signing evidence: raw current signer must be part of raw rotation history"
            }
        }
        if (signingCertificateHistory.isNotEmpty() && signingCertificates.isNotEmpty()) {
            require(signingCertificates.all { currentCert ->
                signingCertificateHistory.any { histCert -> currentCert.contentEquals(histCert) }
            }) {
                "Ambiguous signing evidence: current signing certificate must be part of certificate history"
            }
        }
    }

    val packageIdentity: AndroidPackageName
        get() = AndroidPackageName.of(packageName)

    /**
     * Validates that all raw certificate byte arrays (if present) are valid, parseable X.509 certificates (§1.3, §1.4).
     *
     * Invariant guarantee: When the raw-bytes family is active, validates parseability.
     * Returns true if no raw certificates are configured, or if all configured raw certificates parse successfully.
     */
    fun validateCertificates(): Boolean {
        val certs = if (signingCertificateHistory.isNotEmpty()) {
            signingCertificateHistory
        } else {
            signingCertificates
        }
        if (certs.isEmpty()) return true
        return certs.all { CertificateUtils.isValidX509Certificate(it) }
    }

    /**
     * Extracts all SHA-256 fingerprints (formatted as uppercase AA:BB:CC:... hex).
     *
     * Invariant guarantee: Because exactly one representation family is populated,
     * this method evaluates only the single active family without skipping or ignoring
     * conflicting populated representations from other families.
     *
     * Guarantees non-lossy transformation: does not silently drop corrupted raw certificates or strings.
     */
    fun getAllSha256Fingerprints(): List<String> {
        if (rawRotationHistory.isNotEmpty()) {
            return rawRotationHistory
        }
        if (rotationHistory.isNotEmpty()) {
            return rotationHistory.map { it.value }
        }
        if (rawCurrentFingerprints.isNotEmpty()) {
            return rawCurrentFingerprints
        }
        if (currentFingerprints.isNotEmpty()) {
            return currentFingerprints.map { it.value }
        }
        val certs = if (signingCertificateHistory.isNotEmpty()) {
            signingCertificateHistory
        } else {
            signingCertificates
        }
        return certs.map { certBytes ->
            if (CertificateUtils.isValidX509Certificate(certBytes)) {
                CertificateUtils.computeSha256FingerprintOrNull(certBytes) ?: "MALFORMED_CERTIFICATE_HASH"
            } else {
                "MALFORMED_CERTIFICATE_BYTES"
            }
        }
    }

    /**
     * Safely extracts all certificates as strongly-typed [CertificateDigest] instances,
     * or returns null if any certificate is malformed, corrupted, or unparseable.
     *
     * Invariant guarantee: Because exactly one representation family is populated,
     * this method evaluates only the single active family without skipping or ignoring
     * conflicting populated representations from other families.
     */
    fun getAllCertificateDigestsOrNull(): List<CertificateDigest>? {
        if (!validateCertificates()) return null
        if (rawRotationHistory.isNotEmpty()) {
            val digests = mutableListOf<CertificateDigest>()
            for (fp in rawRotationHistory) {
                val digest = CertificateDigest.fromHexOrNull(fp) ?: return null
                digests.add(digest)
            }
            return digests
        }
        if (rotationHistory.isNotEmpty()) {
            return rotationHistory
        }
        if (rawCurrentFingerprints.isNotEmpty()) {
            val digests = mutableListOf<CertificateDigest>()
            for (fp in rawCurrentFingerprints) {
                val digest = CertificateDigest.fromHexOrNull(fp) ?: return null
                digests.add(digest)
            }
            return digests
        }
        if (currentFingerprints.isNotEmpty()) {
            return currentFingerprints.toList()
        }
        val certs = if (signingCertificateHistory.isNotEmpty()) {
            signingCertificateHistory
        } else {
            signingCertificates
        }
        if (certs.isEmpty()) return emptyList()
        val digests = mutableListOf<CertificateDigest>()
        for (certBytes in certs) {
            val fp = CertificateUtils.computeSha256FingerprintOrNull(certBytes) ?: return null
            val digest = CertificateDigest.fromHexOrNull(fp) ?: return null
            digests.add(digest)
        }
        return digests
    }

    /**
     * Extracts all certificates as strongly-typed [CertificateDigest] instances.
     */
    fun getAllCertificateDigests(): List<CertificateDigest> {
        return getAllCertificateDigestsOrNull().orEmpty()
    }

    /**
     * Returns the primary (latest) signing certificate fingerprint, or null if multi-signer / empty / corrupted.
     *
     * Invariant guarantee: Evaluates only the single active representation family.
     */
    fun getLatestSha256FingerprintOrNull(): String? {
        if (hasMultipleSigners || !validateCertificates()) {
            return null
        }
        if (rawCurrentFingerprints.isNotEmpty()) {
            return rawCurrentFingerprints.firstOrNull()
        }
        if (currentFingerprints.isNotEmpty()) {
            return currentFingerprints.firstOrNull()?.value
        }
        if (rawRotationHistory.isNotEmpty()) {
            return rawRotationHistory.lastOrNull()
        }
        if (rotationHistory.isNotEmpty()) {
            return rotationHistory.lastOrNull()?.value
        }
        val targetCert = signingCertificateHistory.lastOrNull() ?: signingCertificates.firstOrNull()
        return targetCert?.let { CertificateUtils.computeSha256FingerprintOrNull(it) }
    }

    /**
     * Returns the primary (latest) signing certificate digest, or null if multi-signer / empty / corrupted.
     */
    fun getLatestCertificateDigestOrNull(): CertificateDigest? {
        return getLatestSha256FingerprintOrNull()?.let { CertificateDigest.fromHexOrNull(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppSigningInfo
        if (packageName != other.packageName) return false
        if (currentFingerprints != other.currentFingerprints) return false
        if (rotationHistory != other.rotationHistory) return false
        if (rawCurrentFingerprints != other.rawCurrentFingerprints) return false
        if (rawRotationHistory != other.rawRotationHistory) return false
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
        result = 31 * result + currentFingerprints.hashCode()
        result = 31 * result + rotationHistory.hashCode()
        result = 31 * result + rawCurrentFingerprints.hashCode()
        result = 31 * result + rawRotationHistory.hashCode()
        result = 31 * result + signingCertificates.fold(0) { acc, bytes -> acc * 31 + bytes.contentHashCode() }
        result = 31 * result + signingCertificateHistory.fold(0) { acc, bytes -> acc * 31 + bytes.contentHashCode() }
        return result
    }

    companion object {
        /**
         * Creates an [AppSigningInfo] from pre-computed uppercase colon-separated SHA-256 fingerprints.
         *
         * Requires unambiguous input: single fingerprint for single-signer apps.
         * For multi-signers, use [fromMultiSigners]. For key rotation, use [fromRotationHistory].
         *
         * Populates the raw-string representation family.
         */
        @JvmStatic
        fun fromFingerprints(
            packageName: String,
            fingerprints: List<String>
        ): AppSigningInfo {
            require(fingerprints.isNotEmpty()) { "Fingerprints list must not be empty" }
            if (fingerprints.size > 1) {
                throw IllegalArgumentException(
                    "Ambiguous fingerprints for package '$packageName': multiple fingerprints provided (${fingerprints.size}). " +
                    "Use AppSigningInfo.fromMultiSigners(...) for concurrent signers or AppSigningInfo.fromRotationHistory(...) for key rotation."
                )
            }
            return AppSigningInfo(
                packageName = packageName,
                rawCurrentFingerprints = fingerprints
            )
        }

        /**
         * Creates an [AppSigningInfo] from [X509Certificate] instances.
         * Derives multi-signer status structurally from the number of certificates.
         *
         * Populates the raw-bytes representation family.
         */
        @JvmStatic
        fun fromX509Certificates(
            packageName: String,
            certificates: List<X509Certificate>
        ): AppSigningInfo {
            require(certificates.isNotEmpty()) { "Certificates list must not be empty" }
            return AppSigningInfo(
                packageName = packageName,
                signingCertificates = certificates.map { it.encoded }
            )
        }

        /**
         * Creates an [AppSigningInfo] with multiple current APK signers.
         * Enforces fail-closed multi-signer status structurally (§7.2, §10.3).
         *
         * Populates the raw-string representation family.
         */
        @JvmStatic
        fun fromMultiSigners(
            packageName: String,
            fingerprints: List<String>
        ): AppSigningInfo {
            require(fingerprints.size > 1) {
                "Multi-signer application requires at least two distinct signing certificates, got: ${fingerprints.size}"
            }
            return AppSigningInfo(
                packageName = packageName,
                rawCurrentFingerprints = fingerprints
            )
        }

        /**
         * Creates an [AppSigningInfo] representing an application with signing-key rotation history (§2.3).
         *
         * Populates the raw-string representation family.
         */
        @JvmStatic
        fun fromRotationHistory(
            packageName: String,
            historyFingerprints: List<String>
        ): AppSigningInfo {
            require(historyFingerprints.isNotEmpty()) { "Rotation history must not be empty" }
            return AppSigningInfo(
                packageName = packageName,
                rawCurrentFingerprints = listOfNotNull(historyFingerprints.lastOrNull()),
                rawRotationHistory = historyFingerprints
            )
        }

        /**
         * Creates an [AppSigningInfo] from strongly-typed [AndroidPackageName] and [CertificateDigest]s.
         *
         * Populates the typed representation family.
         */
        @JvmStatic
        fun fromTypedValues(
            packageIdentity: AndroidPackageName,
            currentSigners: Collection<CertificateDigest>,
            rotationHistory: List<CertificateDigest> = emptyList()
        ): AppSigningInfo {
            val currentSet = currentSigners.toSet()
            val history = if (rotationHistory.isNotEmpty()) {
                if (!rotationHistory.containsAll(currentSet)) {
                    rotationHistory + currentSet
                } else {
                    rotationHistory
                }
            } else {
                emptyList()
            }
            return AppSigningInfo(
                packageName = packageIdentity.value,
                currentFingerprints = currentSet,
                rotationHistory = history
            )
        }
    }
}
