package org.upspa.catalog

/**
 * Strongly-typed value representation of an account reference.
 *
 * Invariants:
 * - non-empty
 * - no leading/trailing whitespace (`value == value.trim()`); reject, never trim
 * - must not contain `|` (the derivation identity separator)
 * - no control characters (code points < 0x20 or == 0x7F)
 * - length <= 256 UTF-16 code units (documented assumption)
 * - case is preserved: `Alice` and `alice` are distinct references
 * - [toString] returns a redacted representation to prevent sensitive identifier leakage
 */
@JvmInline
value class AccountReference(val value: String) {
    init {
        require(value.isNotEmpty()) { "Account reference must not be empty" }
        require(value == value.trim()) { "Account reference must not contain leading or trailing whitespace" }
        require(!value.contains('|')) { "Account reference must not contain separator '|'" }
        require(value.length <= 256) { "Account reference length must be <= 256 characters, got ${value.length}" }
        require(!hasUnpairedSurrogate(value)) { "Account reference must not contain unpaired surrogates" }
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            require(cp >= 0x20 && cp != 0x7F) { "Account reference must not contain control characters" }
            i += Character.charCount(cp)
        }
    }

    override fun toString(): String = "AccountReference(<redacted>)"
}

internal fun hasUnpairedSurrogate(s: CharSequence): Boolean {
    var i = 0
    val len = s.length
    while (i < len) {
        val ch = s[i]
        if (ch.isHighSurrogate()) {
            if (i + 1 < len && s[i + 1].isLowSurrogate()) {
                i += 2
            } else {
                return true
            }
        } else if (ch.isLowSurrogate()) {
            return true
        } else {
            i += 1
        }
    }
    return false
}

