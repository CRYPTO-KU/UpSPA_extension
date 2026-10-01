package org.upspa.assetlinks.model

import java.net.URI
import java.net.URL

/**
 * Represents a strictly validated Web Origin (scheme + host + port).
 *
 * In accordance with UpSPA Mobile Architecture Research (§1.4 & §8.1),
 * origins are sensitive to exact subdomains and ports.
 * `https://www.example.com` is distinct from `https://example.com`.
 */
class Origin(
    scheme: String,
    host: String,
    val port: Int
) {
    val scheme: String = scheme.lowercase()
    val host: String = host.lowercase()

    init {
        require(this.scheme == "https" || this.scheme == "http") {
            "Only HTTP and HTTPS schemes are supported, got: '$scheme'"
        }
        validateHost(this.host)
        require(port in 1..65535) { "Port must be in range 1..65535, got: $port" }
    }

    operator fun component1(): String = scheme
    operator fun component2(): String = host
    operator fun component3(): Int = port

    fun copy(
        scheme: String = this.scheme,
        host: String = this.host,
        port: Int = this.port
    ): Origin = Origin(scheme, host, port)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Origin) return false
        return scheme == other.scheme && host == other.host && port == other.port
    }

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port.hashCode()
        return result
    }

    val isHttps: Boolean
        get() = scheme.equals("https", ignoreCase = true)

    val isDefaultPort: Boolean
        get() = (scheme.equals("https", ignoreCase = true) && port == 443) ||
                (scheme.equals("http", ignoreCase = true) && port == 80)

    /**
     * Converts to [CanonicalWebOrigin].
     */
    fun toCanonicalOrigin(): CanonicalWebOrigin = CanonicalWebOrigin(this)

    /**
     * Serializes origin into standard URI string format: `scheme://host[:port]`.
     */
    fun toOriginString(): String {
        return if (isDefaultPort) {
            "${scheme.lowercase()}://${host.lowercase()}"
        } else {
            "${scheme.lowercase()}://${host.lowercase()}:$port"
        }
    }

    /**
     * Returns the target URL for retrieving the Digital Asset Links statement list:
     * `scheme://host[:port]/.well-known/assetlinks.json`
     */
    fun toWellKnownAssetLinksUrl(): String {
        return "${toOriginString()}/.well-known/assetlinks.json"
    }

    override fun toString(): String = toOriginString()

    companion object {
        private val FORBIDDEN_HOST_CHARS = charArrayOf('/', '\\', '?', '#', '@', ' ', '\t', '\r', '\n')

        private fun validateIpv6Literal(host: String) {
            val inner = host.substring(1, host.length - 1)
            require(inner.isNotBlank() && inner.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) {
                "Invalid IPv6 host literal: '$host'"
            }
            try {
                val uri = URI.create("https://$host")
                val parsedHost = uri.host
                require(parsedHost != null && parsedHost.equals(host, ignoreCase = true)) {
                    "Invalid IPv6 host literal: '$host'"
                }
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid IPv6 host literal: '$host'", e)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid IPv6 host literal: '$host'", e)
            }
        }

        private fun validateHost(host: String) {
            require(host.isNotBlank()) { "Host must not be blank" }
            require(!host.any { it in FORBIDDEN_HOST_CHARS }) {
                "Host must not contain path, query, fragment, userinfo, or whitespace characters, got: '$host'"
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                validateIpv6Literal(host)
            } else {
                require(!host.contains(':')) {
                    "Host must not contain port or unbracketed colons, got: '$host'"
                }
                require(!host.startsWith(".") && !host.endsWith(".") && !host.contains("..")) {
                    "Host contains invalid dot placement: '$host'"
                }
                require(host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) {
                    "Host contains invalid characters: '$host'"
                }
            }
        }

        /**
         * Parses a URL or URI string into an [Origin].
         *
         * @param rawUrl Target web URL or origin string.
         * @return [Origin] instance.
         * @throws IllegalArgumentException If format is invalid or scheme is unsupported.
         */
        @JvmStatic
        fun parse(rawUrl: String): Origin {
            val uri = try {
                URI.create(rawUrl)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid URL/Origin format: $rawUrl", e)
            }

            val scheme = uri.scheme?.lowercase()
                ?: throw IllegalArgumentException("Origin must have a scheme (http/https): $rawUrl")

            val host = uri.host?.lowercase()
                ?: throw IllegalArgumentException("Origin must have a valid host: $rawUrl")

            validateHost(host)

            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port != -1) uri.port else defaultPort

            return Origin(scheme, host, port)
        }

        /**
         * Safely attempts to parse an [Origin], returning null on failure.
         */
        @JvmStatic
        fun parseOrNull(rawUrl: String): Origin? {
            return try {
                parse(rawUrl)
            } catch (_: Exception) {
                null
            }
        }
    }
}
