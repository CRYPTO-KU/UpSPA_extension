package org.upspa.assetlinks.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class TypedValuesTest {

    private val validFingerprintHex = "14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17"

    @Test
    fun `test AndroidPackageName validates valid and invalid package names`() {
        assertTrue(AndroidPackageName.isValid("com.example.app"))
        assertTrue(AndroidPackageName.isValid("org.upspa.mobile.auth"))
        assertTrue(AndroidPackageName.isValid("com.example_test._hidden.app123"))

        assertFalse(AndroidPackageName.isValid(""))
        assertFalse(AndroidPackageName.isValid("com"))
        assertFalse(AndroidPackageName.isValid("com..example"))
        assertFalse(AndroidPackageName.isValid("1com.example"))
        assertFalse(AndroidPackageName.isValid("com.example.app-invalid"))
        assertFalse(AndroidPackageName.isValid("com.example.app/path"))

        val valid = AndroidPackageName.of("com.example.app")
        assertEquals("com.example.app", valid.value)
        assertEquals("com.example.app", valid.toString())

        assertNull(AndroidPackageName.ofOrNull("invalid_single_segment"))
        assertNotNull(AndroidPackageName.ofOrNull("org.upspa.valid"))

        assertThrows(IllegalArgumentException::class.java) {
            AndroidPackageName("single")
        }
    }

    @Test
    fun `test CertificateDigest validates and normalizes SHA-256 hex strings`() {
        val digest = CertificateDigest.fromHex(validFingerprintHex)
        assertEquals(validFingerprintHex, digest.value)
        assertEquals(validFingerprintHex, digest.toString())

        // Lowercase without colons
        val rawNoColons = validFingerprintHex.replace(":", "").lowercase()
        val normalizedDigest = CertificateDigest.fromHex(rawNoColons)
        assertEquals(validFingerprintHex, normalizedDigest.value)

        // Invalid length throws
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromHex("14:6D:E9:SHORT")
        }

        // Non-hex character throws
        assertThrows(IllegalArgumentException::class.java) {
            CertificateDigest.fromHex(validFingerprintHex.replace("14", "ZZ"))
        }

        assertNull(CertificateDigest.fromHexOrNull("NOT_VALID"))

        // From bytes
        val certBytes = "TestCertData".toByteArray(StandardCharsets.UTF_8)
        val computedDigest = CertificateDigest.fromCertificateBytes(certBytes)
        assertEquals(95, computedDigest.value.length)
    }

    @Test
    fun `test CanonicalWebOrigin enforces exact origin semantics`() {
        val canonical = CanonicalWebOrigin.parse("https://auth.example.com:443/login?q=1")
        assertEquals("https", canonical.scheme)
        assertEquals("auth.example.com", canonical.host)
        assertEquals(443, canonical.port)
        assertTrue(canonical.isHttps)
        assertEquals("https://auth.example.com", canonical.toOriginString())
        assertEquals("https://auth.example.com/.well-known/assetlinks.json", canonical.toWellKnownAssetLinksUrl())

        val enrolled = EnrolledOrigin.of(canonical)
        assertEquals("https://auth.example.com", enrolled.toOriginString())
        assertTrue(enrolled.isHttps)
    }

    @Test
    fun `test RequestedIdentity wraps origin, package name, and required relation`() {
        val canonical = CanonicalWebOrigin.parse("https://auth.example.com")
        val pkg = AndroidPackageName.of("com.example.app")
        val relation = "delegate_permission/common.handle_all_urls"

        val identity = RequestedIdentity.of(canonical, pkg, relation)
        assertEquals(canonical.origin, identity.origin)
        assertEquals(pkg, identity.packageName)
        assertEquals(relation, identity.requiredRelation)
    }

    @Test
    fun `test AppSigningInfo typed extensions and rotation history`() {
        val pkg = AndroidPackageName.of("com.example.app")
        val cert1 = CertificateDigest.fromHex(validFingerprintHex)
        val cert2 = CertificateDigest.fromHex("2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19:2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19")

        // Single signer typed
        val app = AppSigningInfo.fromTypedValues(pkg, listOf(cert1))
        assertEquals(pkg, app.packageIdentity)
        assertEquals(cert1, app.getLatestCertificateDigestOrNull())
        assertEquals(listOf(cert1), app.getAllCertificateDigests())
        assertFalse(app.hasMultipleSigners)

        // Multi-signer typed
        val multiApp = AppSigningInfo.fromMultiSigners(pkg.value, listOf(cert1.value, cert2.value))
        assertTrue(multiApp.hasMultipleSigners)
        assertNull(multiApp.getLatestCertificateDigestOrNull())

        // Rotation history
        val rotatedApp = AppSigningInfo.fromRotationHistory(pkg.value, listOf(cert1.value, cert2.value))
        assertFalse(rotatedApp.hasMultipleSigners)
        assertEquals(2, rotatedApp.getAllCertificateDigests().size)
    }
}
