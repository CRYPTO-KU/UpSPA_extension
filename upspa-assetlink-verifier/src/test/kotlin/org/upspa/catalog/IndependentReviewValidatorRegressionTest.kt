package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Regression tests for the independent review of PR #33 (catalog validator):
 *
 * Finding A: Moshi matches the JSON keywords `true`, `false` and `null` case-insensitively, so
 * `TRUE`, `True`, `FALSE` or `NULL` were accepted as booleans and nulls. RFC 8259 Section 3 defines
 * the literal names in lowercase only.
 *
 * Finding B: Moshi accepts the escape `\'` (backslash followed by an apostrophe) in strings and
 * keys. RFC 8259 Section 7 allows only `\" \\ \/ \b \f \n \r \t` and `\uXXXX`.
 */
class IndependentReviewValidatorRegressionTest {

    private val validator = StrictCatalogValidator()

    /**
     * A valid single-entry catalog. [requireUpper] and [aliasTail] are inserted verbatim as JSON
     * tokens; [allowedSymbols] is inserted verbatim between quotes.
     */
    private fun catalogJson(
        requireUpper: String = "true",
        requireLower: String = "true",
        allowedSymbols: String = "!@#$",
        aliasStatus: String = "MANUAL",
        aliasTail: String = "",
        encoderCounter: String = "0",
        extraRootField: String = ""
    ): String = "{\n" +
        "  \"schemaVersion\": 1,\n" +
        extraRootField +
        "  \"entries\": [\n" +
        "    {\n" +
        "      \"enrolledOrigin\": \"https://example.com\",\n" +
        "      \"accountReference\": \"synthetic_user\",\n" +
        "      \"compatibilityProfileVersion\": 1,\n" +
        "      \"encoderCounter\": $encoderCounter,\n" +
        "      \"policy\": {\n" +
        "        \"minLen\": 16,\n" +
        "        \"maxLen\": 16,\n" +
        "        \"requireUpper\": $requireUpper,\n" +
        "        \"requireLower\": $requireLower,\n" +
        "        \"requireDigit\": true,\n" +
        "        \"requireSymbol\": false,\n" +
        "        \"allowedSymbols\": \"$allowedSymbols\",\n" +
        "        \"forbidWhitespace\": false,\n" +
        "        \"forbiddenSubstrings\": []\n" +
        "      },\n" +
        "      \"aliasEvidence\": {\n" +
        "        \"status\": \"$aliasStatus\"$aliasTail\n" +
        "      }\n" +
        "    }\n" +
        "  ]\n" +
        "}"

    private fun assertRejected(json: String, expected: CatalogRejection, what: String) {
        val result = validator.validate(json)
        assertTrue(result is CatalogValidationResult.Rejected, "Expected Rejected for $what, but got $result")
        assertEquals(expected, (result as CatalogValidationResult.Rejected).rejection, "Wrong rejection for $what")
    }

    private fun assertAccepted(json: String, what: String): CatalogDocument {
        val result = validator.validate(json)
        assertTrue(result is CatalogValidationResult.Accepted, "Expected Accepted for $what, but got $result")
        return (result as CatalogValidationResult.Accepted).document
    }

    // -------------------------------------------------------------
    // Finding A: keywords in a letter case other than lowercase
    // -------------------------------------------------------------

    @ParameterizedTest(name = "requireUpper = {0}")
    @ValueSource(strings = ["TRUE", "True", "tRuE", "truE", "FALSE", "False", "fALSE", "falsE"])
    fun `Finding A - non-lowercase boolean keyword in a policy field is rejected as MalformedJson`(keyword: String) {
        val json = catalogJson(requireUpper = keyword)
        assertTrue(json.contains("\"requireUpper\": $keyword,"), "Fixture must contain the keyword verbatim")
        assertRejected(json, CatalogRejection.MalformedJson, "requireUpper = $keyword")
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
        "reference, NULL",
        "reference, Null",
        "reference, nULL",
        "unverifiedReason, NULL",
        "unverifiedReason, Null",
        "unverifiedReason, nulL"
    )
    fun `Finding A - non-lowercase null keyword in an optional alias field is rejected as MalformedJson`(field: String, keyword: String) {
        // PENDING permits an absent or null reference and requires an absent or null unverifiedReason.
        val json = catalogJson(aliasStatus = "PENDING", aliasTail = ",\n        \"$field\": $keyword")
        assertTrue(json.contains("\"$field\": $keyword\n"), "Fixture must contain the keyword verbatim")
        assertRejected(json, CatalogRejection.MalformedJson, "$field = $keyword")
    }

    @Test
    fun `Finding A - non-lowercase keyword as an array element is rejected as MalformedJson`() {
        val json = catalogJson(extraRootField = "  \"zz\": [true, TRUE],\n")
        assertRejected(json, CatalogRejection.MalformedJson, "TRUE inside an array")
    }

    @Test
    fun `Finding A - valid control - lowercase true and false are accepted with their values`() {
        val doc = assertAccepted(catalogJson(requireUpper = "true", requireLower = "false"), "lowercase true/false")
        assertEquals(true, doc.entries[0].policy.requireUpper)
        assertEquals(false, doc.entries[0].policy.requireLower)
    }

    @ParameterizedTest(name = "{0}: null")
    @ValueSource(strings = ["reference", "unverifiedReason"])
    fun `Finding A - valid control - lowercase null in an optional alias field is accepted as absent`(field: String) {
        val doc = assertAccepted(
            catalogJson(aliasStatus = "PENDING", aliasTail = ",\n        \"$field\": null"),
            "$field = null"
        )
        val alias = doc.entries[0].aliasEvidence
        assertEquals(AliasEvidenceStatus.PENDING, alias.status)
        assertEquals(null, alias.reference)
        assertEquals(null, alias.unverifiedReason)
    }

    @ParameterizedTest(name = "encoderCounter = {0}")
    @ValueSource(strings = ["1e0", "1E0", "0e5", "1.0", "1.5E+2"])
    fun `Finding A - valid control - exponent letters in numbers still yield WrongType, not MalformedJson`(number: String) {
        assertRejected(
            catalogJson(encoderCounter = number),
            CatalogRejection.WrongType("/entries/0/encoderCounter"),
            "encoderCounter = $number"
        )
    }

    @Test
    fun `Finding A - valid control - uppercase keyword after the root value still yields TrailingData`() {
        assertRejected(catalogJson() + " TRUE", CatalogRejection.TrailingData, "trailing TRUE")
    }

    @Test
    fun `Finding A - valid control - uppercase keyword text inside a string is accepted`() {
        val doc = assertAccepted(catalogJson(allowedSymbols = "TRUE-NL!"), "TRUE inside a string")
        assertEquals("TRUE-NL!", doc.entries[0].policy.allowedSymbols)
    }

    // -------------------------------------------------------------
    // Finding B: backslash followed by an apostrophe
    // -------------------------------------------------------------

    @Test
    fun `Finding B - escape backslash-apostrophe in a string value is rejected as MalformedJson`() {
        val json = catalogJson(allowedSymbols = "!" + '\\' + "'@")
        assertTrue(json.contains("\"!\\'@\""), "Fixture must contain backslash followed by an apostrophe")
        assertRejected(json, CatalogRejection.MalformedJson, "backslash-apostrophe in allowedSymbols")
    }

    @Test
    fun `Finding B - escape backslash-apostrophe in an object key is rejected as MalformedJson`() {
        val json = catalogJson(extraRootField = "  \"zz" + '\\' + "'zz\": 1,\n")
        assertTrue(json.contains("\"zz\\'zz\""), "Fixture must contain backslash followed by an apostrophe")
        assertRejected(json, CatalogRejection.MalformedJson, "backslash-apostrophe in a key")
    }

    @ParameterizedTest(name = "escape {0} decodes to U+{1}")
    @CsvSource(
        "'\\\"', 0022",
        "'\\\\', 005C",
        "'\\/', 002F",
        "'\\b', 0008",
        "'\\f', 000C",
        "'\\n', 000A",
        "'\\r', 000D",
        "'\\t', 0009",
        "'\\u0041', 0041",
        "'\\u00e9', 00E9",
        "'\\u00E9', 00E9",
        "'\\uD83D\\uDE00', 1F600"
    )
    fun `Finding B - valid control - RFC 8259 escape is accepted and decoded`(escape: String, codePointHex: String) {
        assertEquals('\\', escape[0], "Escape must start with a backslash")
        val doc = assertAccepted(catalogJson(allowedSymbols = "!$escape@"), "escape $escape")
        val expected = "!" + String(Character.toChars(codePointHex.toInt(16))) + "@"
        assertEquals(expected, doc.entries[0].policy.allowedSymbols, "Decoded value of escape $escape")
    }

    @ParameterizedTest(name = "escape {0}")
    @ValueSource(strings = ["\\a", "\\0", "\\x41", "\\v", "\\U0041", "\\e", "\\u12", "\\u00G1", "\\uZZZZ"])
    fun `Finding B - valid control - non-RFC escape stays rejected as MalformedJson`(escape: String) {
        assertEquals('\\', escape[0], "Escape must start with a backslash")
        assertRejected(catalogJson(allowedSymbols = "!$escape@"), CatalogRejection.MalformedJson, "escape $escape")
    }

    @Test
    fun `Finding B - valid control - short unicode escape at the end of a string stays rejected`() {
        assertRejected(catalogJson(allowedSymbols = "!\\u12"), CatalogRejection.MalformedJson, "trailing short \\u escape")
    }
}
