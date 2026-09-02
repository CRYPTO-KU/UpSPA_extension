package org.upspa.assetlinks.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class CertificateUtilsTest {

    @Test
    fun `test computeSha256Fingerprint returns uppercase colon separated hex`() {
        val input = "UpSPA-Test-Certificate".toByteArray(StandardCharsets.UTF_8)
        val fingerprint = CertificateUtils.computeSha256Fingerprint(input)

        // Must have 32 bytes = 31 colons = 95 characters total length
        assertEquals(95, fingerprint.length)
        val parts = fingerprint.split(":")
        assertEquals(32, parts.size)
        for (part in parts) {
            assertEquals(2, part.length)
            assertEquals(part.uppercase(), part)
        }
    }

    @Test
    fun `test normalizeFingerprint handles various input formats`() {
        val validColonSeparated = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"
        val lowerNoColons = "146de9a1b2c3d4e5f60102030405060708090a0b0c0d0e0f1011121314151617"
        val spaceSeparated = "14 6D E9 A1 B2 C3 D4 E5 F6 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17"

        assertEquals(validColonSeparated, CertificateUtils.normalizeFingerprint(lowerNoColons))
        assertEquals(validColonSeparated, CertificateUtils.normalizeFingerprint(spaceSeparated))
        assertEquals(validColonSeparated, CertificateUtils.normalizeFingerprint(validColonSeparated))
    }

    @Test
    fun `test invalid fingerprint normalization throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            CertificateUtils.normalizeFingerprint("TOO:SHORT")
        }
        assertNull(CertificateUtils.normalizeFingerprintOrNull("INVALID_HEX_DATA"))
    }
}
