package org.upspa.catalog

/**
 * Catalog Schema Version.
 * Currently supported set is exactly `{1}`.
 */
@JvmInline
value class CatalogSchemaVersion(val value: Int) {
    init {
        require(value == 1) { "Unsupported catalog schema version: $value. Supported versions: {1}" }
    }

    companion object {
        @JvmStatic
        fun ofOrNull(value: Int): CatalogSchemaVersion? =
            if (value == 1) CatalogSchemaVersion(value) else null
    }
}

/**
 * Compatibility Profile Version.
 * Currently supported set is exactly `{1}`.
 */
@JvmInline
value class CompatibilityProfileVersion(val value: Int) {
    init {
        require(value == 1) { "Unsupported compatibility profile version: $value. Supported versions: {1}" }
    }

    companion object {
        @JvmStatic
        fun ofOrNull(value: Int): CompatibilityProfileVersion? =
            if (value == 1) CompatibilityProfileVersion(value) else null
    }
}
