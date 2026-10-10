package org.upspa.assetlinks

import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AndroidPackageName
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.CanonicalWebOrigin
import org.upspa.assetlinks.model.CertificateDigest
import org.upspa.assetlinks.model.RequestedIdentity
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifier

/**
 * CLI Demonstration entrypoint for UpSPA AssetLink Verification & Relationship Mapping Module.
 */
fun main(args: Array<String>) {
    println("==========================================================================")
    println(" UpSPA AssetLink Verification & Relationship Mapping Engine (Kotlin/JVM)")
    println(" Research Compliance: §1 (AssetLinks), §2.3 (Rotation), §7.2 (Anti-Patterns)")
    println("==========================================================================")

    // 1. Strongly Typed Domain Values
    val canonicalOrigin = CanonicalWebOrigin.parse("https://auth.example.com")
    val packageName = AndroidPackageName.of("org.upspa.mobile")
    val certDigest = CertificateDigest.fromHex("14:6D:E9:A1:B2:C3:D4:E5:F6:01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17")
    val requestedIdentity = RequestedIdentity.of(canonicalOrigin, packageName, "delegate_permission/common.handle_all_urls")

    println("\n[1] Strongly Typed Identity Model:")
    println("  - Canonical Web Origin: $canonicalOrigin (HTTPS: ${canonicalOrigin.isHttps})")
    println("  - Android Package Name: $packageName")
    println("  - Certificate Digest:   $certDigest")
    println("  - Requested Relation:   ${requestedIdentity.requiredRelation}")

    // 2. Pure Network-Isolated Verification via FakeAssetLinkFetcher
    val assetLinksJson = """
        [
          {
            "relation": ["delegate_permission/common.handle_all_urls"],
            "target": {
              "namespace": "android_app",
              "package_name": "$packageName",
              "sha256_cert_fingerprints": ["$certDigest"]
            }
          }
        ]
    """.trimIndent()

    val fakeFetcher = FakeAssetLinkFetcher.withJson(canonicalOrigin.origin, assetLinksJson)
    val pureVerifier = UpSpaAssetLinkVerifier(fetcher = fakeFetcher)
    val appInfo = AppSigningInfo.fromTypedValues(packageName, listOf(certDigest))

    println("\n[2] Pure Verification with Injected Evidence (Zero Network I/O):")
    val result = pureVerifier.verify(requestedIdentity, appInfo)
    when (result) {
        is VerificationResult.Verified -> {
            println("  -> SUCCESS: Verified relationship between $canonicalOrigin and $packageName")
            println("  -> Matched Fingerprint: ${result.matchedFingerprint}")
            println("  -> Granted Relations: ${result.relations}")
        }
        is VerificationResult.Rejected -> {
            println("  -> UNEXPECTED REJECTION: ${result.reason}")
        }
    }

    // 3. Multi-Signer Fail-Closed Safety (§7.2)
    println("\n[3] Multi-Signer Crash Protection (§7.2 Anti-Pattern Fix):")
    val multiSignerApp = AppSigningInfo.fromMultiSigners(
        packageName = packageName.value,
        fingerprints = listOf(certDigest.value, "2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19:2A:3B:4C:5D:6E:7F:80:91:A2:B3:C4:D5:E6:F7:08:19")
    )
    val multiSignerResult = pureVerifier.verify(requestedIdentity, multiSignerApp)
    println("  - Multi-Signer Result: $multiSignerResult")
    when (multiSignerResult) {
        is VerificationResult.Rejected.MultipleSignersUnsupported -> {
            println("  -> SUCCESS: Fail-closed rejection without crash or unhandled exception!")
        }
        else -> println("  -> Unexpected result: $multiSignerResult")
    }

    // 4. Adversarial Non-HTTPS Negative Control Check
    println("\n[4] Fail-Closed Security Negative Control (Insecure HTTP Scheme):")
    val insecureOrigin = CanonicalWebOrigin.parse("http://auth.example.com")
    val insecureIdentity = RequestedIdentity.of(insecureOrigin, packageName)
    val insecureResult = pureVerifier.verify(insecureIdentity, appInfo)
    println("  - Insecure Origin Result: $insecureResult")
    when (insecureResult) {
        is VerificationResult.Rejected.NonHttpsOrigin -> {
            println("  -> SUCCESS: Strictly rejected insecure HTTP origin without making requests!")
        }
        else -> println("  -> Unexpected result: $insecureResult")
    }

    println("\n[5] Ready for Test Suite (JUnit 5 + Adversarial Negative Control Suite)...")
    println("Run './gradlew test' to execute all verification scenarios.")
}
