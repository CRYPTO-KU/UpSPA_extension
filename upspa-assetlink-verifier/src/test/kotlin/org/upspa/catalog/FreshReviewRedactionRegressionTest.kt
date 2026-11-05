package org.upspa.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.EnrolledOrigin

/**
 * Regression tests for the fresh review of PR #33 at head 9c99b60 (P2):
 *
 * `CatalogEntry.toString()` interpolated the whole policy, and `NormalizedPasswordPolicy.toString()`
 * printed raw `forbiddenSubstrings` and `allowedSymbols`. A policy can legitimately forbid the account
 * name or the site hostname, so logging a "redacted" entry exposed those identifiers.
 *
 * All identifiers are synthetic. Tests named `control` passed before the fix and must keep passing:
 * they show that the redaction does not print nothing, and that the classes that were already safe
 * stay safe.
 */
class FreshReviewRedactionRegressionTest {

    private val validator = StrictCatalogValidator()

    private val account = "synthetic_user"
    private val mixedCaseAccount = "Synthetic_User"
    private val host = "synthetic-host.example.com"
    private val origin = "https://$host"
    private val symbols = "!@#%^&*"
    private val packageName = "org.synthetic.app"
    private val digest = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"

    private val manualAlias = "{\n        \"status\": \"MANUAL\"\n      }"

    private fun verifiedAlias(sourceOrigin: String = origin): String = "{\n" +
        "        \"status\": \"VERIFIED\",\n" +
        "        \"reference\": {\n" +
        "          \"packageName\": \"$packageName\",\n" +
        "          \"signingCertificateDigest\": \"$digest\",\n" +
        "          \"sourceOrigin\": \"$sourceOrigin\"\n" +
        "        }\n" +
        "      }"

    /** A fully populated schema-v1 catalog with one entry. */
    private fun catalogJson(
        enrolledOrigin: String = origin,
        accountReference: String = account,
        forbiddenSubstrings: List<String> = emptyList(),
        aliasEvidence: String = manualAlias
    ): String {
        val forbiddenJson = forbiddenSubstrings.joinToString(",") { "\"$it\"" }
        return "{\n" +
            "  \"schemaVersion\": 1,\n" +
            "  \"entries\": [\n" +
            "    {\n" +
            "      \"enrolledOrigin\": \"$enrolledOrigin\",\n" +
            "      \"accountReference\": \"$accountReference\",\n" +
            "      \"compatibilityProfileVersion\": 1,\n" +
            "      \"encoderCounter\": 7,\n" +
            "      \"policy\": {\n" +
            "        \"minLen\": 20,\n" +
            "        \"maxLen\": 32,\n" +
            "        \"requireUpper\": true,\n" +
            "        \"requireLower\": false,\n" +
            "        \"requireDigit\": true,\n" +
            "        \"requireSymbol\": false,\n" +
            "        \"allowedSymbols\": \"$symbols\",\n" +
            "        \"forbidWhitespace\": true,\n" +
            "        \"forbiddenSubstrings\": [$forbiddenJson]\n" +
            "      },\n" +
            "      \"aliasEvidence\": $aliasEvidence\n" +
            "    }\n" +
            "  ]\n" +
            "}"
    }

    private fun acceptedDocument(json: String): CatalogDocument {
        val result = validator.validate(json)
        assertTrue(result is CatalogValidationResult.Accepted, "Setup: catalog must be accepted, got $result")
        return (result as CatalogValidationResult.Accepted).document
    }

    private fun acceptedEntry(json: String): CatalogEntry = acceptedDocument(json).entries.single()

    private fun policy(
        allowedSymbols: String = symbols,
        forbiddenSubstrings: List<String> = emptyList()
    ) = NormalizedPasswordPolicy(
        minLen = 20,
        maxLen = 32,
        requireUpper = true,
        requireLower = false,
        requireDigit = true,
        requireSymbol = false,
        allowedSymbols = allowedSymbols,
        forbidWhitespace = true,
        forbiddenSubstrings = forbiddenSubstrings
    )

    private fun entry(
        enrolledOrigin: String = origin,
        accountReference: String = account,
        forbiddenSubstrings: List<String> = emptyList()
    ) = CatalogEntry(
        enrolledOrigin = EnrolledOrigin.parse(enrolledOrigin),
        accountReference = AccountReference(accountReference),
        policy = policy(forbiddenSubstrings = forbiddenSubstrings),
        encoderCounter = EncoderCounter(7),
        compatibilityProfileVersion = CompatibilityProfileVersion(1),
        aliasEvidence = AliasEvidence(AliasEvidenceStatus.MANUAL)
    )

    private fun assertOmits(rendered: String, identifier: String, what: String) {
        assertFalse(
            rendered.contains(identifier, ignoreCase = true),
            "$what must not contain '$identifier': $rendered"
        )
    }

    private val expectedPolicySummary = "NormalizedPasswordPolicy(minLen=20, maxLen=32, requireUpper=true, " +
        "requireLower=false, requireDigit=true, requireSymbol=false, forbidWhitespace=true, " +
        "allowedSymbolsCount=7, forbiddenSubstringsCount=1)"

    // -------------------------------------------------------------
    // Reviewer cases through the real validator
    // -------------------------------------------------------------

    @Test
    fun `reviewer case 1 - validated entry does not render an account repeated in forbiddenSubstrings`() {
        val entry = acceptedEntry(catalogJson(forbiddenSubstrings = listOf(account)))
        assertEquals(account, entry.accountReference.value)
        assertOmits(entry.toString(), account, "CatalogEntry.toString")
    }

    @Test
    fun `reviewer case 2 - validated entry does not render a hostname repeated in forbiddenSubstrings`() {
        val entry = acceptedEntry(catalogJson(forbiddenSubstrings = listOf(host)))
        assertEquals(origin, entry.enrolledOrigin.toOriginString())
        assertOmits(entry.toString(), host, "CatalogEntry.toString")
    }

    @Test
    fun `validated VERIFIED entry does not render a hostname repeated in forbiddenSubstrings`() {
        val entry = acceptedEntry(
            catalogJson(forbiddenSubstrings = listOf(host), aliasEvidence = verifiedAlias())
        )
        assertEquals(AliasEvidenceStatus.VERIFIED, entry.aliasEvidence.status)
        assertOmits(entry.toString(), host, "CatalogEntry.toString")
    }

    // -------------------------------------------------------------
    // The same cases through direct constructors
    // -------------------------------------------------------------

    @Test
    fun `direct entry does not render an account repeated in forbiddenSubstrings`() {
        assertOmits(entry(forbiddenSubstrings = listOf(account)).toString(), account, "CatalogEntry.toString")
    }

    @Test
    fun `direct entry does not render a hostname repeated in forbiddenSubstrings`() {
        assertOmits(entry(forbiddenSubstrings = listOf(host)).toString(), host, "CatalogEntry.toString")
    }

    @Test
    fun `direct entry does not render the raw allowedSymbols value`() {
        assertOmits(entry().toString(), symbols, "CatalogEntry.toString")
    }

    @Test
    fun `entry renders the safe policy summary and nothing raw`() {
        assertEquals(
            "CatalogEntry(enrolledOrigin=<redacted>, accountReference=<redacted>, " +
                "compatibilityProfileVersion=1, encoderCounter=7, policy=$expectedPolicySummary, " +
                "aliasEvidence=AliasEvidence(status=MANUAL, reference=null, unverifiedReason=null))",
            entry(forbiddenSubstrings = listOf(account)).toString()
        )
    }

    // -------------------------------------------------------------
    // The policy on its own
    // -------------------------------------------------------------

    @Test
    fun `policy toString omits every forbiddenSubstrings item`() {
        val rendered = policy(forbiddenSubstrings = listOf(account, host, "synthetic-brand")).toString()
        assertOmits(rendered, account, "NormalizedPasswordPolicy.toString")
        assertOmits(rendered, host, "NormalizedPasswordPolicy.toString")
        assertOmits(rendered, "synthetic-brand", "NormalizedPasswordPolicy.toString")
    }

    @Test
    fun `policy toString omits the raw allowedSymbols value`() {
        assertOmits(policy().toString(), symbols, "NormalizedPasswordPolicy.toString")
    }

    @Test
    fun `policy toString is exactly the safe summary`() {
        assertEquals(expectedPolicySummary, policy(forbiddenSubstrings = listOf(account)).toString())
    }

    @Test
    fun `policy summary counts allowedSymbols in code points`() {
        // Pins the unit of the count only. It says nothing about whether non-ASCII symbols are acceptable.
        val rendered = policy(allowedSymbols = "!😀").toString()
        assertTrue(rendered.contains("allowedSymbolsCount=2"), "Expected 2 code points: $rendered")
    }

    @Test
    fun `policies differing only in raw strings render identically`() {
        assertEquals(
            policy(allowedSymbols = "!@#", forbiddenSubstrings = listOf(account)).toString(),
            policy(allowedSymbols = "$%^", forbiddenSubstrings = listOf(host)).toString()
        )
    }

    // -------------------------------------------------------------
    // Containers that render entries
    // -------------------------------------------------------------

    @Test
    fun `entries list rendering omits identifiers repeated in forbiddenSubstrings`() {
        val document = acceptedDocument(catalogJson(forbiddenSubstrings = listOf(account, host)))
        val rendered = document.entries.toString()
        assertOmits(rendered, account, "entries.toString")
        assertOmits(rendered, host, "entries.toString")
    }

    // -------------------------------------------------------------
    // Mixed case: policy items are lowercase by invariant, the account keeps its case
    // -------------------------------------------------------------

    @Test
    fun `validated entry does not render the lowercase form of a mixed-case account`() {
        val entry = acceptedEntry(
            catalogJson(accountReference = mixedCaseAccount, forbiddenSubstrings = listOf(account))
        )
        assertEquals(mixedCaseAccount, entry.accountReference.value)
        assertOmits(entry.toString(), mixedCaseAccount, "CatalogEntry.toString")
    }

    @Test
    fun `direct entry does not render the lowercase form of a mixed-case account`() {
        val entry = entry(accountReference = mixedCaseAccount, forbiddenSubstrings = listOf(account))
        assertOmits(entry.toString(), mixedCaseAccount, "CatalogEntry.toString")
    }

    // -------------------------------------------------------------
    // Valid controls
    // -------------------------------------------------------------

    @Test
    fun `control - validated entry with empty forbiddenSubstrings renders no identifier`() {
        val rendered = acceptedEntry(catalogJson()).toString()
        assertOmits(rendered, account, "CatalogEntry.toString")
        assertOmits(rendered, host, "CatalogEntry.toString")
    }

    @Test
    fun `control - direct entry with empty forbiddenSubstrings renders no identifier`() {
        val rendered = entry().toString()
        assertOmits(rendered, account, "CatalogEntry.toString")
        assertOmits(rendered, host, "CatalogEntry.toString")
    }

    @Test
    fun `control - entry toString still shows the safe fields`() {
        val rendered = acceptedEntry(catalogJson()).toString()
        for (field in listOf(
            "minLen=20", "maxLen=32", "requireUpper=true", "requireLower=false", "requireDigit=true",
            "requireSymbol=false", "forbidWhitespace=true", "encoderCounter=7",
            "compatibilityProfileVersion=1", "status=MANUAL"
        )) {
            assertTrue(rendered.contains(field), "CatalogEntry.toString must show $field: $rendered")
        }
    }

    @Test
    fun `control - policy toString still shows lengths and flags`() {
        val rendered = policy().toString()
        for (field in listOf(
            "minLen=20", "maxLen=32", "requireUpper=true", "requireLower=false", "requireDigit=true",
            "requireSymbol=false", "forbidWhitespace=true"
        )) {
            assertTrue(rendered.contains(field), "Policy toString must show $field: $rendered")
        }
    }

    @Test
    fun `control - document and accepted result stay count-only with a leaking policy`() {
        val json = catalogJson(forbiddenSubstrings = listOf(account, host))
        val result = validator.validate(json)
        assertTrue(result is CatalogValidationResult.Accepted, "Setup: catalog must be accepted, got $result")
        val document = (result as CatalogValidationResult.Accepted).document

        assertEquals("CatalogDocument(schemaVersion=1, entryCount=1)", document.toString())
        assertEquals("Accepted(document=CatalogDocument(schemaVersion=1, entryCount=1))", result.toString())
    }

    @Test
    fun `control - alias evidence renders package and digest but no origin`() {
        val reference = AliasEvidenceReference(
            AndroidPackageName.of(packageName),
            CertificateDigest.fromHex(digest),
            EnrolledOrigin.parse(origin)
        )
        val evidence = AliasEvidence(AliasEvidenceStatus.VERIFIED, reference = reference)

        for (rendered in listOf(reference.toString(), evidence.toString())) {
            assertOmits(rendered, host, "Alias evidence toString")
            assertTrue(rendered.contains(packageName), "Package name stays visible: $rendered")
            assertTrue(rendered.contains(digest), "Certificate digest stays visible: $rendered")
        }
    }

    @Test
    fun `control - alias evidence renders the unverified reason as an enum code`() {
        val evidence = AliasEvidence(
            AliasEvidenceStatus.UNVERIFIED,
            unverifiedReason = UnverifiedReason.EVIDENCE_STALE
        )
        assertEquals(
            "AliasEvidence(status=UNVERIFIED, reference=null, unverifiedReason=EVIDENCE_STALE)",
            evidence.toString()
        )
    }

    @Test
    fun `control - account reference stays redacted`() {
        assertEquals("AccountReference(<redacted>)", AccountReference(account).toString())
    }

    @Test
    fun `control - rejection does not echo an identifier used as a key under an unknown field`() {
        val json = "{\"schemaVersion\":1,\"$account\":{\"a\":1,\"a\":2},\"entries\":[]}"
        val result = validator.validate(json)
        assertEquals(CatalogValidationResult.Rejected(CatalogRejection.UnknownField("")), result)
        assertOmits(result.toString(), account, "Rejection toString")
    }

    @Test
    fun `control - rejection does not echo an identifier used as a key under a wrong-type field`() {
        val json = catalogJson(accountReference = "PLACEHOLDER").replace(
            "\"PLACEHOLDER\"",
            "{\"$account\":{\"a\":1,\"a\":2}}"
        )
        val result = validator.validate(json)
        assertEquals(
            CatalogValidationResult.Rejected(CatalogRejection.WrongType("/entries/0/accountReference")),
            result
        )
        assertOmits(result.toString(), account, "Rejection toString")
    }

    @Test
    fun `control - equality and hashing still use the raw policy strings`() {
        val forbidsAccount = policy(forbiddenSubstrings = listOf(account))
        val forbidsHost = policy(forbiddenSubstrings = listOf(host))
        val otherSymbols = policy(allowedSymbols = "!@#")

        assertNotEquals(forbidsAccount, forbidsHost)
        assertNotEquals(forbidsAccount.hashCode(), forbidsHost.hashCode())
        assertNotEquals(policy(), otherSymbols)
        assertEquals(forbidsAccount, policy(forbiddenSubstrings = listOf(account)))
        assertEquals(forbidsAccount.hashCode(), policy(forbiddenSubstrings = listOf(account)).hashCode())
        assertNotEquals(entry(forbiddenSubstrings = listOf(account)), entry(forbiddenSubstrings = listOf(host)))
    }

    @Test
    fun `control - redaction leaves the raw policy values readable to callers`() {
        val entry = acceptedEntry(catalogJson(forbiddenSubstrings = listOf(account, host)))
        assertEquals(listOf(account, host), entry.policy.forbiddenSubstrings)
        assertEquals(symbols, entry.policy.allowedSymbols)
    }
}
