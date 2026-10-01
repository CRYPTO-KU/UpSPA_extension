package org.upspa.assetlinks.fetcher

import org.upspa.assetlinks.model.Origin

/**
 * Result returned by the raw HTTP fetcher layer.
 */
sealed interface FetchResult {
    data class Success(val jsonBody: String, val contentType: String) : FetchResult
    data class Redirect(val statusCode: Int, val location: String?) : FetchResult
    data class HttpError(val statusCode: Int, val message: String) : FetchResult
    data class InvalidContentType(val actualContentType: String?) : FetchResult
    data class NetworkFailure(val cause: Throwable) : FetchResult
}

/**
 * Interface for fetching the raw Digital Asset Links JSON from an origin (§1.2).
 */
interface AssetLinkFetcher {
    /**
     * Fetches the `.well-known/assetlinks.json` content from the specified [origin].
     *
     * MUST strictly enforce followRedirects = false and validate Content-Type = application/json.
     */
    fun fetchAssetLinks(origin: Origin): FetchResult
}
