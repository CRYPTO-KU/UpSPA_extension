package org.upspa.catalog

import com.squareup.moshi.JsonReader
import okio.Buffer
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.EnrolledOrigin
import org.upspa.assetlinks.model.Origin
import java.io.IOException
import java.util.Locale

/**
 * Public catalog validator interface.
 */
interface CatalogValidator {
    fun validate(rawJson: String): CatalogValidationResult
}

/**
 * Strict catalog validator implementing fail-closed schema v1 validation.
 *
 * Enforces non-lenient parsing, max 64 KiB input, nesting depth <= 8,
 * duplicate key detection, unknown field rejection, type verification,
 * exact origin normalization checks, policy invariants, and alias evidence rules.
 */
open class StrictCatalogValidator : CatalogValidator {

    final override fun validate(rawJson: String): CatalogValidationResult {
        return try {
            doValidate(rawJson)
        } catch (_: Exception) {
            CatalogValidationResult.Rejected(CatalogRejection.InternalValidationError)
        }
    }

    internal open fun doValidate(rawJson: String): CatalogValidationResult {
        // Reject unpaired surrogates in raw string before UTF-8 conversion
        if (hasUnpairedSurrogate(rawJson)) {
            return CatalogValidationResult.Rejected(CatalogRejection.UnpairedSurrogate)
        }

        // 1. Size check: max 64 KiB (65,536 UTF-8 bytes)
        val utf8Bytes = rawJson.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > MAX_INPUT_BYTES) {
            return CatalogValidationResult.Rejected(CatalogRejection.InputTooLarge)
        }

        // 2. Parse into AST with streaming Moshi JsonReader
        val parseResult = parseJsonAst(utf8Bytes)
        if (parseResult is AstResult.Rejected) {
            return CatalogValidationResult.Rejected(parseResult.rejection)
        }
        val rootNode = (parseResult as AstResult.Success).root

        // 3. Document-level checks
        if (rootNode !is AstNode.JsonObject) {
            return CatalogValidationResult.Rejected(CatalogRejection.WrongType(""))
        }

        if (rootNode.duplicateKeyPath != null) {
            return CatalogValidationResult.Rejected(CatalogRejection.DuplicateJsonKey(rootNode.duplicateKeyPath))
        }

        // Check unknown fields in root
        for (field in rootNode.fields) {
            if (field.name !in ROOT_ALLOWED_FIELDS) {
                return CatalogValidationResult.Rejected(CatalogRejection.UnknownField(""))
            }
        }

        // Check required root fields: schemaVersion and entries
        val schemaVerNode = rootNode.get("schemaVersion")
            ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("/schemaVersion"))
        val entriesNode = rootNode.get("entries")
            ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("/entries"))

        // Validate schemaVersion
        if (schemaVerNode !is AstNode.JsonNumber || !INTEGER_LEXEME_REGEX.matches(schemaVerNode.raw)) {
            return CatalogValidationResult.Rejected(CatalogRejection.WrongType("/schemaVersion"))
        }
        val schemaVerValue = schemaVerNode.raw.toLongOrNull()
            ?: return CatalogValidationResult.Rejected(CatalogRejection.UnsupportedSchemaVersion(Long.MAX_VALUE))
        if (schemaVerValue != 1L) {
            return CatalogValidationResult.Rejected(CatalogRejection.UnsupportedSchemaVersion(schemaVerValue))
        }
        val schemaVersion = CatalogSchemaVersion(1)

        // Validate entries list
        if (entriesNode !is AstNode.JsonArray) {
            return CatalogValidationResult.Rejected(CatalogRejection.WrongType("/entries"))
        }

        val catalogEntries = ArrayList<CatalogEntry>(entriesNode.elements.size)

        // 4. Validate entries in array order
        for ((entryIdx, entryElement) in entriesNode.elements.withIndex()) {
            val entryPath = "/entries/$entryIdx"
            if (entryElement !is AstNode.JsonObject) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType(entryPath))
            }

            if (entryElement.duplicateKeyPath != null) {
                return CatalogValidationResult.Rejected(CatalogRejection.DuplicateJsonKey(entryElement.duplicateKeyPath))
            }

            // Check unknown fields in entry
            for (field in entryElement.fields) {
                if (field.name !in ENTRY_ALLOWED_FIELDS) {
                    return CatalogValidationResult.Rejected(CatalogRejection.UnknownField(entryPath))
                }
            }

            // Inside each entry in order:
            // a. enrolledOrigin
            if (!entryElement.has("enrolledOrigin")) {
                return CatalogValidationResult.Rejected(CatalogRejection.MissingEnrolledOrigin(entryIdx))
            }
            val originNode = entryElement.get("enrolledOrigin")!!
            if (originNode is AstNode.JsonNull) {
                return CatalogValidationResult.Rejected(CatalogRejection.MissingEnrolledOrigin(entryIdx))
            }
            if (originNode !is AstNode.JsonString) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$entryPath/enrolledOrigin"))
            }
            val originStr = originNode.value
            val parsedOrigin = try {
                Origin.parse(originStr)
            } catch (_: Exception) {
                return CatalogValidationResult.Rejected(CatalogRejection.InvalidEnrolledOrigin(entryIdx))
            }
            if (parsedOrigin.toOriginString() != originStr) {
                return CatalogValidationResult.Rejected(CatalogRejection.NonCanonicalEnrolledOrigin(entryIdx))
            }
            val enrolledOrigin = EnrolledOrigin.of(parsedOrigin)

            // b. accountReference
            val accountNode = entryElement.get("accountReference")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$entryPath/accountReference"))
            if (accountNode !is AstNode.JsonString) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$entryPath/accountReference"))
            }
            val accountRefStr = accountNode.value
            val accountRefValidation = validateAccountReferenceString(entryIdx, accountRefStr)
            if (accountRefValidation != null) {
                return CatalogValidationResult.Rejected(accountRefValidation)
            }
            val accountReference = AccountReference(accountRefStr)

            // c. compatibilityProfileVersion
            val compatNode = entryElement.get("compatibilityProfileVersion")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$entryPath/compatibilityProfileVersion"))
            if (compatNode !is AstNode.JsonNumber || !INTEGER_LEXEME_REGEX.matches(compatNode.raw)) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$entryPath/compatibilityProfileVersion"))
            }
            val compatVerVal = compatNode.raw.toLongOrNull()
                ?: return CatalogValidationResult.Rejected(CatalogRejection.UnsupportedCompatibilityProfileVersion(entryIdx, Long.MAX_VALUE))
            if (compatVerVal != 1L) {
                return CatalogValidationResult.Rejected(CatalogRejection.UnsupportedCompatibilityProfileVersion(entryIdx, compatVerVal))
            }
            val compatibilityProfileVersion = CompatibilityProfileVersion(1)

            // d. encoderCounter
            val counterNode = entryElement.get("encoderCounter")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$entryPath/encoderCounter"))
            if (counterNode !is AstNode.JsonNumber || !INTEGER_LEXEME_REGEX.matches(counterNode.raw)) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$entryPath/encoderCounter"))
            }
            val counterVal = counterNode.raw.toLongOrNull()
            if (counterVal == null || counterVal !in EncoderCounter.MIN_VALUE..EncoderCounter.MAX_VALUE) {
                return CatalogValidationResult.Rejected(CatalogRejection.InvalidEncoderCounter(entryIdx))
            }
            val encoderCounter = EncoderCounter(counterVal)

            // e. policy
            val policyNodeRaw = entryElement.get("policy")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$entryPath/policy"))
            val policyPath = "$entryPath/policy"
            if (policyNodeRaw !is AstNode.JsonObject) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType(policyPath))
            }
            val policyNode = policyNodeRaw
            if (policyNode.duplicateKeyPath != null) {
                return CatalogValidationResult.Rejected(CatalogRejection.DuplicateJsonKey(policyNode.duplicateKeyPath))
            }
            for (pField in policyNode.fields) {
                if (pField.name !in POLICY_ALLOWED_FIELDS) {
                    return CatalogValidationResult.Rejected(CatalogRejection.UnknownField(policyPath))
                }
            }
            // Check missing any field -> PartialPolicy
            for (reqKey in POLICY_REQUIRED_FIELDS) {
                if (!policyNode.has(reqKey)) {
                    return CatalogValidationResult.Rejected(CatalogRejection.PartialPolicy(entryIdx))
                }
            }
            // Validate policy field types
            val minLenField = policyNode.get("minLen")!!
            if (minLenField !is AstNode.JsonNumber || !INTEGER_LEXEME_REGEX.matches(minLenField.raw)) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/minLen"))
            }
            val minLen = minLenField.raw.toIntOrNull()
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.LENGTH_OUT_OF_RANGE))

            val maxLenField = policyNode.get("maxLen")!!
            if (maxLenField !is AstNode.JsonNumber || !INTEGER_LEXEME_REGEX.matches(maxLenField.raw)) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/maxLen"))
            }
            val maxLen = maxLenField.raw.toIntOrNull()
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.LENGTH_OUT_OF_RANGE))

            val reqUpperField = policyNode.get("requireUpper")!!
            if (reqUpperField !is AstNode.JsonBoolean) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/requireUpper"))
            }
            val reqLowerField = policyNode.get("requireLower")!!
            if (reqLowerField !is AstNode.JsonBoolean) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/requireLower"))
            }
            val reqDigitField = policyNode.get("requireDigit")!!
            if (reqDigitField !is AstNode.JsonBoolean) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/requireDigit"))
            }
            val reqSymbolField = policyNode.get("requireSymbol")!!
            if (reqSymbolField !is AstNode.JsonBoolean) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/requireSymbol"))
            }
            val allowedSymbolsField = policyNode.get("allowedSymbols")!!
            if (allowedSymbolsField !is AstNode.JsonString) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/allowedSymbols"))
            }
            val forbidWsField = policyNode.get("forbidWhitespace")!!
            if (forbidWsField !is AstNode.JsonBoolean) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/forbidWhitespace"))
            }
            val forbiddenSubsField = policyNode.get("forbiddenSubstrings")!!
            if (forbiddenSubsField !is AstNode.JsonArray) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/forbiddenSubstrings"))
            }
            val forbiddenSubs = ArrayList<String>(forbiddenSubsField.elements.size)
            for (subElem in forbiddenSubsField.elements) {
                if (subElem !is AstNode.JsonString) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$policyPath/forbiddenSubstrings"))
                }
                forbiddenSubs.add(subElem.value)
            }

            // Validate policy invariants
            val policyValidation = validatePolicyInvariants(
                entryIdx = entryIdx,
                minLen = minLen,
                maxLen = maxLen,
                requireSymbol = reqSymbolField.value,
                allowedSymbols = allowedSymbolsField.value,
                forbidWhitespace = forbidWsField.value,
                forbiddenSubstrings = forbiddenSubs
            )
            if (policyValidation != null) {
                return CatalogValidationResult.Rejected(policyValidation)
            }
            val policy = NormalizedPasswordPolicy(
                minLen = minLen,
                maxLen = maxLen,
                requireUpper = reqUpperField.value,
                requireLower = reqLowerField.value,
                requireDigit = reqDigitField.value,
                requireSymbol = reqSymbolField.value,
                allowedSymbols = allowedSymbolsField.value,
                forbidWhitespace = forbidWsField.value,
                forbiddenSubstrings = forbiddenSubs
            )

            // f. aliasEvidence
            val aliasNodeRaw = entryElement.get("aliasEvidence")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$entryPath/aliasEvidence"))
            val aliasPath = "$entryPath/aliasEvidence"
            if (aliasNodeRaw !is AstNode.JsonObject) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType(aliasPath))
            }
            val aliasNode = aliasNodeRaw
            if (aliasNode.duplicateKeyPath != null) {
                return CatalogValidationResult.Rejected(CatalogRejection.DuplicateJsonKey(aliasNode.duplicateKeyPath))
            }
            for (aField in aliasNode.fields) {
                if (aField.name !in ALIAS_ALLOWED_FIELDS) {
                    return CatalogValidationResult.Rejected(CatalogRejection.UnknownField(aliasPath))
                }
            }

            val statusNode = aliasNode.get("status")
                ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$aliasPath/status"))
            if (statusNode !is AstNode.JsonString) {
                return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$aliasPath/status"))
            }
            val statusStr = statusNode.value
            val status = when (statusStr) {
                "VERIFIED" -> AliasEvidenceStatus.VERIFIED
                "UNVERIFIED" -> AliasEvidenceStatus.UNVERIFIED
                "PENDING" -> AliasEvidenceStatus.PENDING
                "MANUAL" -> AliasEvidenceStatus.MANUAL
                else -> return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.UNKNOWN_STATUS))
            }

            val hasRef = aliasNode.has("reference") && aliasNode.get("reference") !is AstNode.JsonNull
            val hasReason = aliasNode.has("unverifiedReason") && aliasNode.get("unverifiedReason") !is AstNode.JsonNull

            // Cross-field status rules on presence of reference and reason
            when (status) {
                AliasEvidenceStatus.VERIFIED -> {
                    if (!hasRef) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REFERENCE_REQUIRED))
                    if (hasReason) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REASON_FORBIDDEN))
                }
                AliasEvidenceStatus.UNVERIFIED -> {
                    if (!hasReason) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REASON_REQUIRED))
                }
                AliasEvidenceStatus.PENDING -> {
                    if (hasReason) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REASON_FORBIDDEN))
                }
                AliasEvidenceStatus.MANUAL -> {
                    if (hasRef) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REFERENCE_FORBIDDEN))
                    if (hasReason) return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.REASON_FORBIDDEN))
                }
            }

            var unverifiedReason: UnverifiedReason? = null
            if (hasReason) {
                val reasonNode = aliasNode.get("unverifiedReason")!!
                if (reasonNode !is AstNode.JsonString) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$aliasPath/unverifiedReason"))
                }
                val reasonStr = reasonNode.value
                unverifiedReason = when (reasonStr) {
                    "EVIDENCE_MISSING" -> UnverifiedReason.EVIDENCE_MISSING
                    "EVIDENCE_STALE" -> UnverifiedReason.EVIDENCE_STALE
                    "EVIDENCE_UNAVAILABLE" -> UnverifiedReason.EVIDENCE_UNAVAILABLE
                    "VERIFICATION_REJECTED" -> UnverifiedReason.VERIFICATION_REJECTED
                    else -> return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$aliasPath/unverifiedReason"))
                }
            }

            var aliasReference: AliasEvidenceReference? = null
            if (hasRef) {
                val refPath = "$aliasPath/reference"
                val refNodeRaw = aliasNode.get("reference")!!
                if (refNodeRaw !is AstNode.JsonObject) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType(refPath))
                }
                val refNode = refNodeRaw
                if (refNode.duplicateKeyPath != null) {
                    return CatalogValidationResult.Rejected(CatalogRejection.DuplicateJsonKey(refNode.duplicateKeyPath))
                }
                for (rField in refNode.fields) {
                    if (rField.name !in REFERENCE_ALLOWED_FIELDS) {
                        return CatalogValidationResult.Rejected(CatalogRejection.UnknownField(refPath))
                    }
                }

                // packageName
                val pkgNode = refNode.get("packageName")
                    ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$refPath/packageName"))
                if (pkgNode !is AstNode.JsonString) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$refPath/packageName"))
                }
                val pkgStr = pkgNode.value
                if (!AndroidPackageName.isValid(pkgStr)) {
                    return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.INVALID_PACKAGE_NAME))
                }
                val packageName = AndroidPackageName.of(pkgStr)

                // signingCertificateDigest
                val certNode = refNode.get("signingCertificateDigest")
                    ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$refPath/signingCertificateDigest"))
                if (certNode !is AstNode.JsonString) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$refPath/signingCertificateDigest"))
                }
                val certStr = certNode.value
                val certDigest = CertificateDigest.fromHexOrNull(certStr)
                    ?: return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.INVALID_CERTIFICATE_DIGEST))

                // sourceOrigin
                val srcNode = refNode.get("sourceOrigin")
                    ?: return CatalogValidationResult.Rejected(CatalogRejection.MissingField("$refPath/sourceOrigin"))
                if (srcNode !is AstNode.JsonString) {
                    return CatalogValidationResult.Rejected(CatalogRejection.WrongType("$refPath/sourceOrigin"))
                }
                val srcOriginStr = srcNode.value
                val parsedSrcOrigin = try {
                    Origin.parse(srcOriginStr)
                } catch (_: Exception) {
                    return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.INVALID_SOURCE_ORIGIN))
                }
                if (parsedSrcOrigin.toOriginString() != srcOriginStr) {
                    return CatalogValidationResult.Rejected(CatalogRejection.MalformedAliasEvidence(entryIdx, MalformedAliasEvidenceCode.INVALID_SOURCE_ORIGIN))
                }
                val sourceOrigin = EnrolledOrigin.of(parsedSrcOrigin)

                aliasReference = AliasEvidenceReference(packageName, certDigest, sourceOrigin)
            }

            val aliasEvidence = AliasEvidence(status, aliasReference, unverifiedReason)

            // g. Entry cross-field rules
            if (aliasEvidence.reference != null && aliasEvidence.reference.sourceOrigin != enrolledOrigin) {
                return CatalogValidationResult.Rejected(CatalogRejection.AliasOriginMismatch(entryIdx))
            }
            if (aliasEvidence.status == AliasEvidenceStatus.VERIFIED && !enrolledOrigin.isHttps) {
                return CatalogValidationResult.Rejected(CatalogRejection.AliasRequiresHttps(entryIdx))
            }

            catalogEntries.add(
                CatalogEntry(
                    enrolledOrigin = enrolledOrigin,
                    accountReference = accountReference,
                    policy = policy,
                    encoderCounter = encoderCounter,
                    compatibilityProfileVersion = compatibilityProfileVersion,
                    aliasEvidence = aliasEvidence
                )
            )
        }

        // 5. Duplicate detection last
        val seenPairs = HashMap<Pair<EnrolledOrigin, String>, Int>()
        for ((idx, entry) in catalogEntries.withIndex()) {
            val key = Pair(entry.enrolledOrigin, entry.accountReference.value)
            val firstIdx = seenPairs[key]
            if (firstIdx != null) {
                return CatalogValidationResult.Rejected(CatalogRejection.DuplicateEntry(firstIdx, idx))
            }
            seenPairs[key] = idx
        }

        return CatalogValidationResult.Accepted(CatalogDocument(schemaVersion, catalogEntries))
    }

    private fun validateAccountReferenceString(entryIdx: Int, str: String): CatalogRejection? {
        if (hasUnpairedSurrogate(str)) {
            return CatalogRejection.UnpairedSurrogate
        }
        if (str.isEmpty()) {
            return CatalogRejection.InvalidAccountReference(entryIdx, InvalidAccountReferenceCode.EMPTY)
        }
        if (str != str.trim()) {
            return CatalogRejection.InvalidAccountReference(entryIdx, InvalidAccountReferenceCode.WHITESPACE_AT_EDGE)
        }
        if (str.contains('|')) {
            return CatalogRejection.InvalidAccountReference(entryIdx, InvalidAccountReferenceCode.SEPARATOR_PRESENT)
        }
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            if (cp < 0x20 || cp == 0x7F) {
                return CatalogRejection.InvalidAccountReference(entryIdx, InvalidAccountReferenceCode.CONTROL_CHARACTER)
            }
            i += Character.charCount(cp)
        }
        if (str.length > 256) {
            return CatalogRejection.InvalidAccountReference(entryIdx, InvalidAccountReferenceCode.TOO_LONG)
        }
        return null
    }

    private fun validatePolicyInvariants(
        entryIdx: Int,
        minLen: Int,
        maxLen: Int,
        requireSymbol: Boolean,
        allowedSymbols: String,
        forbidWhitespace: Boolean,
        forbiddenSubstrings: List<String>
    ): CatalogRejection? {
        if (minLen < 8) {
            return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.MIN_LEN_TOO_SMALL)
        }
        if (minLen > maxLen) {
            return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.MIN_GREATER_THAN_MAX)
        }
        if (maxLen > 64 && maxLen != minLen) {
            return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.MAX_LEN_ABOVE_CAP)
        }
        val symbolCodePoints = HashSet<Int>()
        var i = 0
        while (i < allowedSymbols.length) {
            val cp = allowedSymbols.codePointAt(i)
            if (!symbolCodePoints.add(cp)) {
                return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.DUPLICATE_SYMBOLS)
            }
            if (forbidWhitespace && (Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
                return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.WHITESPACE_IN_SYMBOLS)
            }
            i += Character.charCount(cp)
        }
        if (requireSymbol && allowedSymbols.isEmpty()) {
            return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.EMPTY_SYMBOLS_WITH_REQUIRE_SYMBOL)
        }
        if (!requireSymbol && allowedSymbols.isEmpty()) {
            return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.EMPTY_SYMBOLS_WITHOUT_REQUIRE_SYMBOL)
        }
        for (sub in forbiddenSubstrings) {
            if (sub.isEmpty() || sub != sub.trim().lowercase(Locale.ROOT)) {
                return CatalogRejection.MalformedPolicy(entryIdx, MalformedPolicyCode.FORBIDDEN_SUBSTRING_NOT_NORMALIZED)
            }
        }
        return null
    }

    private fun validateStrictJsonStringSyntax(utf8Bytes: ByteArray): CatalogRejection? {
        var inString = false
        var escaped = false
        for (b in utf8Bytes) {
            val unsigned = b.toInt() and 0xFF
            if (!inString) {
                if (unsigned == '"'.code) {
                    inString = true
                    escaped = false
                }
            } else {
                if (escaped) {
                    escaped = false
                } else if (unsigned == '\\'.code) {
                    escaped = true
                } else if (unsigned == '"'.code) {
                    inString = false
                } else if (unsigned < 0x20) {
                    // RFC 8259 Section 7: unescaped control characters (< 0x20) are forbidden inside JSON strings
                    return CatalogRejection.MalformedJson
                }
            }
        }
        return null
    }

    private fun parseJsonAst(utf8Bytes: ByteArray): AstResult {
        val syntaxRejection = validateStrictJsonStringSyntax(utf8Bytes)
        if (syntaxRejection != null) {
            return AstResult.Rejected(syntaxRejection)
        }
        val buffer = Buffer().write(utf8Bytes)
        val reader = JsonReader.of(buffer)
        reader.isLenient = false

        return try {
            val root = parseNode(reader, currentDepth = 1, currentPath = "")
            if (root is AstNode.RejectedNode) {
                return AstResult.Rejected(root.rejection)
            }
            try {
                if (reader.peek() != JsonReader.Token.END_DOCUMENT) {
                    return AstResult.Rejected(CatalogRejection.TrailingData)
                }
            } catch (_: Exception) {
                return AstResult.Rejected(CatalogRejection.TrailingData)
            }
            AstResult.Success(root)
        } catch (_: Exception) {
            AstResult.Rejected(CatalogRejection.MalformedJson)
        }
    }

    private fun parseNode(reader: JsonReader, currentDepth: Int, currentPath: String): AstNode {
        if (currentDepth > MAX_NESTING_DEPTH) {
            return AstNode.RejectedNode(CatalogRejection.NestingTooDeep)
        }

        return when (val token = reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject()
                val fields = ArrayList<JsonField>()
                val seenKeys = HashSet<String>()
                var duplicateKeyPath: String? = null

                while (reader.hasNext()) {
                    val keyName = reader.nextName()
                    if (hasUnpairedSurrogate(keyName)) {
                        return AstNode.RejectedNode(CatalogRejection.UnpairedSurrogate)
                    }
                    if (!seenKeys.add(keyName) && duplicateKeyPath == null) {
                        duplicateKeyPath = currentPath
                    }
                    val childPath = if (currentPath.isEmpty()) "/$keyName" else "$currentPath/$keyName"
                    val childNode = parseNode(reader, currentDepth + 1, childPath)
                    if (childNode is AstNode.RejectedNode) {
                        return childNode
                    }
                    fields.add(JsonField(keyName, childNode))
                }
                reader.endObject()
                AstNode.JsonObject(fields, duplicateKeyPath)
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                val elements = ArrayList<AstNode>()
                var idx = 0
                while (reader.hasNext()) {
                    val childPath = "$currentPath/$idx"
                    val childNode = parseNode(reader, currentDepth + 1, childPath)
                    if (childNode is AstNode.RejectedNode) {
                        return childNode
                    }
                    elements.add(childNode)
                    idx++
                }
                reader.endArray()
                AstNode.JsonArray(elements)
            }
            JsonReader.Token.STRING -> {
                val s = reader.nextString()
                if (hasUnpairedSurrogate(s)) {
                    return AstNode.RejectedNode(CatalogRejection.UnpairedSurrogate)
                }
                AstNode.JsonString(s)
            }
            JsonReader.Token.NUMBER -> {
                AstNode.JsonNumber(reader.nextString())
            }
            JsonReader.Token.BOOLEAN -> {
                AstNode.JsonBoolean(reader.nextBoolean())
            }
            JsonReader.Token.NULL -> {
                reader.nextNull<Any?>()
                AstNode.JsonNull
            }
            else -> {
                throw IOException("Unexpected token: $token")
            }
        }
    }

    private data class JsonField(val name: String, val value: AstNode)

    private sealed class AstResult {
        data class Success(val root: AstNode) : AstResult()
        data class Rejected(val rejection: CatalogRejection) : AstResult()
    }

    private sealed class AstNode {
        data class JsonObject(val fields: List<JsonField>, val duplicateKeyPath: String?) : AstNode() {
            fun get(name: String): AstNode? = fields.find { it.name == name }?.value
            fun has(name: String): Boolean = fields.any { it.name == name }
        }
        data class JsonArray(val elements: List<AstNode>) : AstNode()
        data class JsonString(val value: String) : AstNode()
        data class JsonNumber(val raw: String) : AstNode()
        data class JsonBoolean(val value: Boolean) : AstNode()
        object JsonNull : AstNode()
        data class RejectedNode(val rejection: CatalogRejection) : AstNode()
    }

    companion object {
        private const val MAX_INPUT_BYTES = 65536
        private const val MAX_NESTING_DEPTH = 8
        private val INTEGER_LEXEME_REGEX = Regex("^(0|[1-9][0-9]*)$")

        private val ROOT_ALLOWED_FIELDS = setOf("schemaVersion", "entries")
        private val ENTRY_ALLOWED_FIELDS = setOf(
            "enrolledOrigin",
            "accountReference",
            "compatibilityProfileVersion",
            "encoderCounter",
            "policy",
            "aliasEvidence"
        )
        private val POLICY_ALLOWED_FIELDS = setOf(
            "minLen",
            "maxLen",
            "requireUpper",
            "requireLower",
            "requireDigit",
            "requireSymbol",
            "allowedSymbols",
            "forbidWhitespace",
            "forbiddenSubstrings"
        )
        private val POLICY_REQUIRED_FIELDS = POLICY_ALLOWED_FIELDS
        private val ALIAS_ALLOWED_FIELDS = setOf("status", "reference", "unverifiedReason")
        private val REFERENCE_ALLOWED_FIELDS = setOf("packageName", "signingCertificateDigest", "sourceOrigin")
    }
}
