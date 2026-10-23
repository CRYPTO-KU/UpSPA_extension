package org.upspa.assetlinks.model

import java.net.URI

/**
 * Strongly-typed value representation of a canonical Web Origin (§1.4, §8.1).
 *
 * A canonical web origin consists strictly of:
 * - Scheme: "https" (or "http" for testing/internal environments) in lowercase
 * - Host: lowercase domain or IP, free of userinfo, path, query, or fragment
 * - Port: explicit port number, with default port omitted in canonical string form
 */
@JvmInline
value class CanonicalWebOrigin(val origin: Origin) {

    val scheme: String get() = origin.scheme
    val host: String get() = origin.host
    val port: Int get() = origin.port
    val isHttps: Boolean get() = origin.isHttps

    fun toOriginString(): String = origin.toOriginString()
    fun toWellKnownAssetLinksUrl(): String = origin.toWellKnownAssetLinksUrl()

    override fun toString(): String = origin.toOriginString()

    companion object {
        /**
         * Parses a raw URL/URI into a [CanonicalWebOrigin].
         */
        @JvmStatic
        fun parse(rawUrl: String): CanonicalWebOrigin {
            return CanonicalWebOrigin(Origin.parse(rawUrl))
        }

        /**
         * Safely attempts to parse a [CanonicalWebOrigin], returning null on failure.
         */
        @JvmStatic
        fun parseOrNull(rawUrl: String): CanonicalWebOrigin? {
            val origin = Origin.parseOrNull(rawUrl) ?: return null
            return CanonicalWebOrigin(origin)
        }

        /**
         * Wraps an existing [Origin].
         */
        @JvmStatic
        fun of(origin: Origin): CanonicalWebOrigin = CanonicalWebOrigin(origin)
    }
}
