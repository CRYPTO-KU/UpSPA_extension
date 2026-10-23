package org.upspa.assetlinks.fetcher

import org.upspa.assetlinks.model.Origin
import java.util.Collections

/**
 * In-memory, network-isolated implementation of [AssetLinkFetcher] (§1.2).
 *
 * Designed for pure verification and hermetic testing:
 * - Performs **zero network I/O** (no sockets, no threads, no HTTP calls).
 * - Not a silent stub: returns the exact evidence configured for an origin,
 *   forcing the verifier's full cryptographic and structural validation logic to execute.
 * - Records all fetched origins for test assertions and auditability.
 */
class FakeAssetLinkFetcher(
    private val configuredResponses: Map<Origin, FetchResult> = emptyMap(),
    private val defaultResult: FetchResult? = null
) : AssetLinkFetcher {

    private val mutableHistory: MutableList<Origin> = Collections.synchronizedList(mutableListOf())

    /**
     * Unmodifiable list of origins requested from this fetcher in chronological order.
     */
    val recordedRequests: List<Origin>
        get() = synchronized(mutableHistory) { mutableHistory.toList() }

    override fun fetchAssetLinks(origin: Origin): FetchResult {
        mutableHistory.add(origin)
        return configuredResponses[origin]
            ?: defaultResult
            ?: FetchResult.HttpError(
                statusCode = 404,
                message = "Not Found: No Digital Asset Links statement configured in FakeAssetLinkFetcher for $origin"
            )
    }

    companion object {
        /**
         * Creates a fetcher that returns the given JSON body with `application/json` Content-Type
         * for the specified [origin].
         */
        @JvmStatic
        fun withJson(origin: Origin, jsonBody: String): FakeAssetLinkFetcher {
            return FakeAssetLinkFetcher(
                configuredResponses = mapOf(
                    origin to FetchResult.Success(jsonBody = jsonBody, contentType = "application/json")
                )
            )
        }

        /**
         * Creates a fetcher that simulates an HTTP redirect (e.g. 301/302).
         */
        @JvmStatic
        fun withRedirect(origin: Origin, statusCode: Int, location: String?): FakeAssetLinkFetcher {
            return FakeAssetLinkFetcher(
                configuredResponses = mapOf(
                    origin to FetchResult.Redirect(statusCode = statusCode, location = location)
                )
            )
        }

        /**
         * Creates a fetcher that simulates a non-JSON Content-Type response (e.g. text/html login page).
         */
        @JvmStatic
        fun withContentType(origin: Origin, body: String, contentType: String): FakeAssetLinkFetcher {
            val isJson = contentType.split(";").firstOrNull()?.trim()?.equals("application/json", ignoreCase = true) == true
            val result = if (isJson) {
                FetchResult.Success(jsonBody = body, contentType = contentType)
            } else {
                FetchResult.InvalidContentType(actualContentType = contentType)
            }
            return FakeAssetLinkFetcher(
                configuredResponses = mapOf(origin to result)
            )
        }

        /**
         * Creates a fetcher that returns an InvalidContentType result.
         */
        @JvmStatic
        fun withInvalidContentType(origin: Origin, actualContentType: String?): FakeAssetLinkFetcher {
            return FakeAssetLinkFetcher(
                configuredResponses = mapOf(
                    origin to FetchResult.InvalidContentType(actualContentType = actualContentType)
                )
            )
        }

        /**
         * Creates a fetcher that returns an HTTP error code (e.g. 404, 500).
         */
        @JvmStatic
        fun withHttpError(origin: Origin, statusCode: Int, message: String): FakeAssetLinkFetcher {
            return FakeAssetLinkFetcher(
                configuredResponses = mapOf(
                    origin to FetchResult.HttpError(statusCode = statusCode, message = message)
                )
            )
        }

        /**
         * Creates an empty fetcher that fails all requests with HTTP 404.
         */
        @JvmStatic
        fun empty(): FakeAssetLinkFetcher = FakeAssetLinkFetcher()
    }
}
