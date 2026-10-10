package org.upspa.catalog

/**
 * Result of validating a serialized catalog document.
 */
sealed class CatalogValidationResult {
    data class Accepted(val document: CatalogDocument) : CatalogValidationResult()
    data class Rejected(val rejection: CatalogRejection) : CatalogValidationResult()
}

/**
 * Sealed hierarchy of typed catalog validation rejections.
 *
 * Adheres strictly to rejection hygiene: contains only entry indexes, fixed schema paths,
 * enum codes, and numeric versions. Never echoes user identifiers, raw origin strings,
 * or attacker-controlled free text.
 */
sealed class CatalogRejection {
    object InputTooLarge : CatalogRejection() {
        override fun toString(): String = "InputTooLarge"
    }

    object MalformedJson : CatalogRejection() {
        override fun toString(): String = "MalformedJson"
    }

    object TrailingData : CatalogRejection() {
        override fun toString(): String = "TrailingData"
    }

    object NestingTooDeep : CatalogRejection() {
        override fun toString(): String = "NestingTooDeep"
    }

    data class DuplicateJsonKey(val path: String) : CatalogRejection()

    /**
     * Unknown key rejection.
     * Contains only [parentPath]; key name is intentionally omitted to prevent echoing untrusted text.
     */
    data class UnknownField(val parentPath: String) : CatalogRejection()

    data class WrongType(val path: String) : CatalogRejection()

    data class MissingField(val path: String) : CatalogRejection()

    data class MissingEnrolledOrigin(val entryIndex: Int) : CatalogRejection()

    data class UnsupportedSchemaVersion(val found: Long) : CatalogRejection()

    data class UnsupportedCompatibilityProfileVersion(val entryIndex: Int, val found: Long) : CatalogRejection()

    data class InvalidEnrolledOrigin(val entryIndex: Int) : CatalogRejection()

    data class NonCanonicalEnrolledOrigin(val entryIndex: Int) : CatalogRejection()

    data class InvalidAccountReference(val entryIndex: Int, val code: InvalidAccountReferenceCode) : CatalogRejection()

    data class PartialPolicy(val entryIndex: Int) : CatalogRejection()

    data class MalformedPolicy(val entryIndex: Int, val code: MalformedPolicyCode) : CatalogRejection()

    data class InvalidEncoderCounter(val entryIndex: Int) : CatalogRejection()

    data class MalformedAliasEvidence(val entryIndex: Int, val code: MalformedAliasEvidenceCode) : CatalogRejection()

    data class AliasOriginMismatch(val entryIndex: Int) : CatalogRejection()

    data class AliasRequiresHttps(val entryIndex: Int) : CatalogRejection()

    data class DuplicateEntry(val firstIndex: Int, val secondIndex: Int) : CatalogRejection()

    object InternalValidationError : CatalogRejection() {
        override fun toString(): String = "InternalValidationError"
    }
}

enum class InvalidAccountReferenceCode {
    EMPTY,
    WHITESPACE_AT_EDGE,
    SEPARATOR_PRESENT,
    CONTROL_CHARACTER,
    TOO_LONG
}

enum class MalformedPolicyCode {
    MIN_LEN_TOO_SMALL,
    MIN_GREATER_THAN_MAX,
    MAX_LEN_ABOVE_CAP,
    DUPLICATE_SYMBOLS,
    WHITESPACE_IN_SYMBOLS,
    EMPTY_SYMBOLS_WITH_REQUIRE_SYMBOL,
    EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL,
    FORBIDDEN_SUBSTRING_NOT_NORMALIZED,
    LENGTH_OUT_OF_RANGE
}

enum class MalformedAliasEvidenceCode {
    UNKNOWN_STATUS,
    REFERENCE_REQUIRED,
    REFERENCE_FORBIDDEN,
    REASON_REQUIRED,
    REASON_FORBIDDEN,
    INVALID_PACKAGE_NAME,
    INVALID_CERTIFICATE_DIGEST,
    INVALID_SOURCE_ORIGIN
}
