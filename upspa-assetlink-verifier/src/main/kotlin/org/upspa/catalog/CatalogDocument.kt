package org.upspa.catalog

import org.upspa.assetlinks.model.EnrolledOrigin
import java.util.Collections

/**
 * Top-level catalog document containing schema version and enrolled entries.
 *
 * Invariants:
 * - Each (enrolledOrigin, accountReference) pair must be strictly unique (exact equality; case-sensitive).
 * - An empty [entries] list is valid; a missing entries field in serialization is invalid.
 * - [entries] is an immutable snapshot; mutating caller collections after construction cannot mutate the document.
 */
class CatalogDocument(
    val schemaVersion: CatalogSchemaVersion,
    entries: List<CatalogEntry>
) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(entries.toList())

    init {
        val seen = mutableSetOf<Pair<EnrolledOrigin, String>>()
        for (entry in this.entries) {
            val key = Pair(entry.enrolledOrigin, entry.accountReference.value)
            require(seen.add(key)) {
                "Duplicate (enrolledOrigin, accountReference) entry detected in CatalogDocument"
            }
        }
    }

    fun copy(
        schemaVersion: CatalogSchemaVersion = this.schemaVersion,
        entries: List<CatalogEntry> = this.entries
    ): CatalogDocument = CatalogDocument(schemaVersion, entries)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CatalogDocument) return false
        return schemaVersion == other.schemaVersion && entries == other.entries
    }

    override fun hashCode(): Int {
        var result = schemaVersion.hashCode()
        result = 31 * result + entries.hashCode()
        return result
    }

    override fun toString(): String {
        return "CatalogDocument(schemaVersion=${schemaVersion.value}, entryCount=${entries.size})"
    }

    companion object {
        fun of(schemaVersion: CatalogSchemaVersion, entries: List<CatalogEntry>): CatalogDocument {
            return CatalogDocument(schemaVersion, entries)
        }
    }
}
