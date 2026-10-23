package org.upspa.catalog

import java.util.Collections
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
 * 6. `allowedSymbols` is not empty (for both `requireSymbol` true and false)
 * 7. every `forbiddenSubstrings` item is non-empty, equals its own `trim().lowercase(Locale.ROOT)`
 * - [forbiddenSubstrings] is an immutable snapshot; mutating caller collections after construction cannot mutate the policy.
 */
class NormalizedPasswordPolicy(
    val minLen: Int,
    val maxLen: Int,
    val requireUpper: Boolean,
    val requireLower: Boolean,
    val requireDigit: Boolean,
    val requireSymbol: Boolean,
    val allowedSymbols: String,
    val forbidWhitespace: Boolean,
    forbiddenSubstrings: List<String>
) {
    val forbiddenSubstrings: List<String> = Collections.unmodifiableList(forbiddenSubstrings.toList())

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
        } else {
            require(allowedSymbols.isNotEmpty()) { "allowedSymbols must not be empty when requireSymbol is false" }
        }
        for (sub in this.forbiddenSubstrings) {
            require(sub.isNotEmpty()) { "forbiddenSubstrings items must be non-empty" }
            require(sub == sub.trim().lowercase(Locale.ROOT)) {
                "forbiddenSubstrings item must be already trimmed and lowercase (Locale.ROOT)"
            }
        }
    }

    fun copy(
        minLen: Int = this.minLen,
        maxLen: Int = this.maxLen,
        requireUpper: Boolean = this.requireUpper,
        requireLower: Boolean = this.requireLower,
        requireDigit: Boolean = this.requireDigit,
        requireSymbol: Boolean = this.requireSymbol,
        allowedSymbols: String = this.allowedSymbols,
        forbidWhitespace: Boolean = this.forbidWhitespace,
        forbiddenSubstrings: List<String> = this.forbiddenSubstrings
    ): NormalizedPasswordPolicy = NormalizedPasswordPolicy(
        minLen = minLen,
        maxLen = maxLen,
        requireUpper = requireUpper,
        requireLower = requireLower,
        requireDigit = requireDigit,
        requireSymbol = requireSymbol,
        allowedSymbols = allowedSymbols,
        forbidWhitespace = forbidWhitespace,
        forbiddenSubstrings = forbiddenSubstrings
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NormalizedPasswordPolicy) return false
        return minLen == other.minLen &&
                maxLen == other.maxLen &&
                requireUpper == other.requireUpper &&
                requireLower == other.requireLower &&
                requireDigit == other.requireDigit &&
                requireSymbol == other.requireSymbol &&
                allowedSymbols == other.allowedSymbols &&
                forbidWhitespace == other.forbidWhitespace &&
                forbiddenSubstrings == other.forbiddenSubstrings
    }

    override fun hashCode(): Int {
        var result = minLen
        result = 31 * result + maxLen
        result = 31 * result + requireUpper.hashCode()
        result = 31 * result + requireLower.hashCode()
        result = 31 * result + requireDigit.hashCode()
        result = 31 * result + requireSymbol.hashCode()
        result = 31 * result + allowedSymbols.hashCode()
        result = 31 * result + forbidWhitespace.hashCode()
        result = 31 * result + forbiddenSubstrings.hashCode()
        return result
    }

    override fun toString(): String {
        return "NormalizedPasswordPolicy(minLen=$minLen, maxLen=$maxLen, requireUpper=$requireUpper, " +
                "requireLower=$requireLower, requireDigit=$requireDigit, requireSymbol=$requireSymbol, " +
                "allowedSymbols='$allowedSymbols', forbidWhitespace=$forbidWhitespace, forbiddenSubstrings=$forbiddenSubstrings)"
    }
}
