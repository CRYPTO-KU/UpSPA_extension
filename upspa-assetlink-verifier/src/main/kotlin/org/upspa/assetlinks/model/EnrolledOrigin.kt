package org.upspa.assetlinks.model

/**
 * Strongly-typed representation of an Enrolled Web Origin in Digital Asset Links relationship mapping (§1.2).
 *
 * Represents an enrolled principal that publishes statements asserting relationships with Android applications.
 */
@JvmInline
value class EnrolledOrigin(val canonicalOrigin: CanonicalWebOrigin) {

    val origin: Origin get() = canonicalOrigin.origin
    val isHttps: Boolean get() = canonicalOrigin.isHttps

    fun toOriginString(): String = canonicalOrigin.toOriginString()
    fun toWellKnownAssetLinksUrl(): String = canonicalOrigin.toWellKnownAssetLinksUrl()

    override fun toString(): String = canonicalOrigin.toString()

    companion object {
        @JvmStatic
        fun of(origin: Origin): EnrolledOrigin = EnrolledOrigin(CanonicalWebOrigin.of(origin))

        @JvmStatic
        fun of(canonicalOrigin: CanonicalWebOrigin): EnrolledOrigin = EnrolledOrigin(canonicalOrigin)

        @JvmStatic
        fun parse(rawUrl: String): EnrolledOrigin = EnrolledOrigin(CanonicalWebOrigin.parse(rawUrl))

        @JvmStatic
        fun parseOrNull(rawUrl: String): EnrolledOrigin? {
            val canonical = CanonicalWebOrigin.parseOrNull(rawUrl) ?: return null
            return EnrolledOrigin(canonical)
        }
    }
}
