package org.upspa.catalog

import java.util.Locale

/**
 * Strongly-typed Normalized Password Policy representing a fully normalized policy.
 *
 * All nine fields are mandatory. Invariants enforced in [init]:
 * 1. `minLen >= 8`
 * 2. `minLen <= maxLen`
 * 3. if `maxLen > 64` then `maxLen == minLen`
 * 4. `allowedSymbols` has no duplicate code points
 * 5. if `forbidWhitespace` then `allowedSymbols` contains no whitespace code point
 * 6. if `requireSymbol` then `allowedSymbols` is not empty
 * 7. every `forbiddenSubstrings` item is non-empty, equals its own `trim().lowercase(Locale.ROOT)`
 */
data class NormalizedPasswordPolicy(
    val minLen: Int,
    val maxLen: Int,
    val requireUpper: Boolean,
    val requireLower: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val allowedSymbols: String,
    val forbidWhitespace: Boolean,
    val forbiddenSubstrings: List<String>
) {
    init {
        require(minLen >= 8) { "minLen must be >= 8, got $minLen" }
        require(minLen <= maxLen) { "minLen ($minLen) must be <= maxLen ($maxLen)" }
        if (maxLen > 64) {
            require(maxLen == minLen) { "When maxLen ($maxLen) > 64, maxLen must equal minLen ($minLen)" }
        }
        val symbolCodePoints = mutableSetOf<Int>()
        var i = 0
        while (i < allowedSymbols.length) {
            val cp = allowedSymbols.codePointAt(i)
            require(symbolCodePoints.add(cp)) {
                "Duplicate code point in allowedSymbols: U+${Integer.toHexString(cp).uppercase(Locale.ROOT)}"
            }
            if (forbidWhitespace) {
                require(!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                    "Whitespace code point found in allowedSymbols when forbidWhitespace is true"
                }
            }
            i += Character.charCount(cp)
        }
        if (requireSymbol) {
            require(allowedSymbols.isNotEmpty()) { "allowedSymbols must not be empty when requireSymbol is true" }
        }
        for (sub in forbiddenSubstrings) {
            require(sub.isNotEmpty()) { "forbiddenSubstrings items must be non-empty" }
            require(sub == sub.trim().lowercase(Locale.ROOT)) {
                "forbiddenSubstrings item must be already trimmed and lowercase (Locale.ROOT)"
            }
        }
    }
}
