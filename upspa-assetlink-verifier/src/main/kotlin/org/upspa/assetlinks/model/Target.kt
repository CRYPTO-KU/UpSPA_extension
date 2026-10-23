package org.upspa.assetlinks.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Target descriptor inside a Digital Asset Link statement (§1.3).
 *
 * For Android apps:
 * - [namespace] must be "android_app"
 * - [packageName] identifies the package name of the app
 * - [sha256CertFingerprints] contains the uppercase colon-separated SHA-256 certificate fingerprints
 */
@JsonClass(generateAdapter = true)
data class Target(
    @Json(name = "namespace")
    val namespace: String? = null,

    @Json(name = "package_name")
    val packageName: String? = null,

    @Json(name = "sha256_cert_fingerprints")
    val sha256CertFingerprints: List<String> = emptyList(),

    // Web target attributes (if statement targets a website)
    @Json(name = "site")
    val site: String? = null
)
