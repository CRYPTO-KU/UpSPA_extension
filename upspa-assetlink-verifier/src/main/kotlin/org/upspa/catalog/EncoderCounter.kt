package org.upspa.catalog

/**
 * Encoder counter representing an unsigned 32-bit counter (range 0..4294967295).
 * Mirrors Rust `u32`.
 */
@JvmInline
value class EncoderCounter(val value: Long) {
    init {
        require(value in MIN_VALUE..MAX_VALUE) {
            "Encoder counter out of range 0..4294967295: $value"
        }
    }

    companion object {
        const val MIN_VALUE: Long = 0L
        const val MAX_VALUE: Long = 4294967295L
    }
}
