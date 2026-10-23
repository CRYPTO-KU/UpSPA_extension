package org.upspa.assetlinks.model

/**
 * Strongly-typed value representation of an Android application package name (§1.4).
 *
 * Enforces Android package naming conventions:
 * - Must contain at least two segments separated by dots (e.g. `com.example.app`).
 * - Each segment must begin with an ASCII letter or underscore.
 * - Remaining characters in each segment must be alphanumeric or underscore.
 * - No trailing, leading, or consecutive dots.
 */
@JvmInline
value class AndroidPackageName(val value: String) {

    init {
        require(isValid(value)) {
            "Invalid Android package name: '$value'. Must contain at least two segments and follow Java/Android identifier syntax."
        }
    }

    override fun toString(): String = value

    companion object {
        private val PACKAGE_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

        /**
         * Validates whether [name] adheres to strict Android package naming rules.
         */
        @JvmStatic
        fun isValid(name: String): Boolean {
            if (name.isBlank() || name.length > 255) return false
            return PACKAGE_REGEX.matches(name)
        }

        /**
         * Creates an [AndroidPackageName] if valid, or returns null.
         */
        @JvmStatic
        fun ofOrNull(name: String): AndroidPackageName? {
            val trimmed = name.trim()
            return if (isValid(trimmed)) AndroidPackageName(trimmed) else null
        }

        /**
         * Factory function creating [AndroidPackageName].
         */
        @JvmStatic
        fun of(name: String): AndroidPackageName = AndroidPackageName(name.trim())
    }
}
