package org.upspa.assetlinks.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OriginTest {

    @Test
    fun `test exact origin equality and string representation`() {
        val origin1 = Origin.parse("https://example.com")
        val origin2 = Origin.parse("https://example.com:443")
        val originDifferentHost = Origin.parse("https://www.example.com")
        val originDifferentPort = Origin.parse("https://example.com:8443")
        val originDifferentScheme = Origin.parse("http://example.com")

        assertEquals(origin1, origin2)
        assertEquals("https://example.com", origin1.toOriginString())
        assertEquals("https://example.com/.well-known/assetlinks.json", origin1.toWellKnownAssetLinksUrl())

        // Subdomain sensitivity (§1.4)
        assertNotEquals(origin1, originDifferentHost)
        // Port sensitivity (§1.4)
        assertNotEquals(origin1, originDifferentPort)
        // Scheme sensitivity
        assertNotEquals(origin1, originDifferentScheme)
    }

    @Test
    fun `test custom port well known url formatting`() {
        val origin = Origin.parse("https://auth.example.com:9443/login")
        assertEquals("https://auth.example.com:9443", origin.toOriginString())
        assertEquals("https://auth.example.com:9443/.well-known/assetlinks.json", origin.toWellKnownAssetLinksUrl())
    }

    @Test
    fun `test unsupported schemes throw IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            Origin.parse("file:///etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Origin.parse("javascript:alert(1)")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Origin.parse("ftp://ftp.example.com")
        }

        assertNull(Origin.parseOrNull("android:apk-key-hash:abc"))
    }
}
