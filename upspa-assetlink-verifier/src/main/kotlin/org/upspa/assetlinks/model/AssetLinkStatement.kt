package org.upspa.assetlinks.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Statement entry in a `.well-known/assetlinks.json` statement list (§1.3).
 *
 * Each statement asserts a relationship [relation] between the hosting principal (origin)
 * and a target entity [target].
 */
@JsonClass(generateAdapter = true)
data class AssetLinkStatement(
    @Json(name = "relation")
    val relation: List<String> = emptyList(),

    @Json(name = "target")
    val target: Target? = null,

    @Json(name = "include")
    val include: String? = null
)
