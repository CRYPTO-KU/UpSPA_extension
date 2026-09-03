package com.upspa.research.provider

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TEST-ONLY credential generation.
 *
 * Values are deterministic per (requesting package, field role) so experiments are
 * reproducible across runs and devices, and they are unmistakably fake: every value carries
 * a FAKE- prefix so no screenshot or log can be confused with a real secret.
 *
 * The HMAC key below is deliberately public — it protects nothing and must never be reused
 * outside this research artifact. Real UpSPA derivation (threshold TOPRF against storage
 * providers) is out of scope here; its cost is modeled by the caller with a configurable
 * delay instead (see [Prefs.KEY_LATENCY_MS]).
 */
object FakeCredentialFactory {

    private const val RESEARCH_ONLY_KEY = "UPSPA-ANDROID-RESEARCH-LAB-NOT-A-SECRET"

    fun valueFor(requestingPackage: String, role: FieldClassifier.Role): String = when (role) {
        FieldClassifier.Role.USERNAME -> usernameFor(requestingPackage)
        FieldClassifier.Role.EMAIL -> "FAKE-" + hmacHex("email|$requestingPackage").take(8) + "@example.test"
        FieldClassifier.Role.PASSWORD_CURRENT -> "FAKE-pw-" + hmacHex("pw-current|$requestingPackage").take(16)
        FieldClassifier.Role.PASSWORD_NEW -> "FAKE-pw-new-" + hmacHex("pw-new|$requestingPackage").take(16)
        FieldClassifier.Role.OTP -> "000000"
        FieldClassifier.Role.UNKNOWN -> "FAKE-unknown"
    }

    fun usernameFor(requestingPackage: String): String =
        "FAKE-user-" + hmacHex("user|$requestingPackage").take(8)

    private fun hmacHex(input: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(RESEARCH_ONLY_KEY.toByteArray(), "HmacSHA256"))
        return mac.doFinal(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
