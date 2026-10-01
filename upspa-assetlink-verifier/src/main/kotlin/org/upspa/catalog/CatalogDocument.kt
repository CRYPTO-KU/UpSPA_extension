package org.upspa.catalog

import org.upspa.assetlinks.model.EnrolledOrigin

/**
 * Top-level catalog document containing schema version and enrolled entries.
 *
 * Invariants:
 * - Each (enrolledOrigin, accountReference) pair must be strictly unique (exact equality; case-sensitive).
 * - An empty [entries] list is valid; a missing entries field in serialization is invalid.
 */
data class CatalogDocument(
    val schemaVersion: CatalogSchemaVersion,
    val entries: List<CatalogEntry>
) {
    init {
        val seen = mutableSetOf<Pair<EnrolledOrigin, String>>()
        for (entry in entries) {
            val key = Pair(entry.enrolledOrigin, entry.accountReference.value)
            require(seen.add(key)) {
                "Duplicate (enrolledOrigin, accountReference) entry detected in CatalogDocument"
            }
        }
    }

    override fun toString(): String {
        return "CatalogDocument(schemaVersion=${schemaVersion.value}, entryCount=${entries.size})"
    }
}
