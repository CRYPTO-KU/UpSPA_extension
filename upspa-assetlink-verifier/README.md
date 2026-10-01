# UpSPA AssetLink Verification and Relationship Mapping Module (Kotlin/JVM)

This module implements a high-security, crash-resistant, network-isolated **Digital Asset Links** (DAL) verification engine in accordance with the **UpSPA Mobile Architecture Specification** (§1.2, §1.3, §1.4, §1.5, §2.3, §7.2, §10.3). It answers the core security question: *"How can UpSPA safely and deterministically verify that a web origin and an installed native Android application represent the same Login Server?"*

Based on Google's `android/identity-samples` reference implementation, this module eliminates its known vulnerabilities (anti-patterns), introduces strong domain typing, provides a network-isolated pure verification engine, and enforces fail-closed rejection policies validated against an adversarial negative control test suite.

---

## 🚀 Build and Run

```bash
# Run complete test suite (31/31 JUnit 5 tests, including adversarial negative controls)
./gradlew test

# Run the Demonstration CLI
./gradlew run
```

Prerequisites: JDK 17+ (or JDK 11+ compatible). Gradle wrapper (`gradlew`) is included.

---

## 🛡️ Key Architectural & Security Capabilities

| Feature / Requirement | Google Reference Implementation | UpSPA Implementation |
| :--- | :--- | :--- |
| **Network Isolation & Pure Verifier** | Live network calls entangled with verification logic. | `PureAssetLinkVerifier` executes purely in-memory on injected evidence with zero network I/O. `FakeAssetLinkFetcher` enables offline hermetic testing. No `INTERNET` permission required. |
| **Strongly-Typed Domain Values** | Raw `String` primitives used for packages, digests, and origins. | Strong typed representations: `CanonicalWebOrigin`, `AndroidPackageName`, `CertificateDigest`, `RequestedIdentity`, `EnrolledOrigin`. |
| **Exact Matching (Anti-Confusion)** | Vulnerable to substring or subdomain/suffix confusion. | Strictly exact case-sensitive package matching and exact normalized SHA-256 certificate matching (no prefix/suffix/substring matching). |
| **Multi-Signer Crash Safety** | `computeLatestCertification()` inside `!!` assertion causes `NullPointerException` crash. | APK multi-signer condition is detected fail-closed; safely returns `VerificationResult.Rejected.MultipleSignersUnsupported` (§7.2, §10.3). |
| **HTTP Redirect Protection** | Follows HTTP redirects; vulnerable to domain hijacking / spoofing. | Strictly forbids redirects (`followRedirects = false`). Rejection `VerificationResult.Rejected.RedirectAttempted` triggered on 301/302/307/308 (§1.2). |
| **Strict HTTPS Enforcement** | May allow unencrypted HTTP. | Default `allowInsecureHttp = false` rejects non-HTTPS origins fail-closed with `VerificationResult.Rejected.NonHttpsOrigin`. |
| **Content-Type Validation** | Only checks HTTP 200, parses HTML errors as JSON. | Enforces `Content-Type: application/json` header matching; rejects HTML/XML with `InvalidContentType` (§1.2). |
| **APK Key Rotation Lineage** | Only inspects first certificate in signing info. | Full APK v3/v3.1 key rotation history (`signingCertificateHistory`) supported without duplication (§2.3). |
| **Fail-Closed Typed Rejections** | Generic boolean `false` or unhandled exceptions. | Rich `VerificationResult.Rejected` sealed hierarchy with dedicated subtypes per failure category. |

---

## 📁 Module Architecture

```
upspa-assetlink-verifier/
├── build.gradle.kts                      # Gradle Kotlin DSL configuration
├── settings.gradle.kts                   # Settings configuration
├── src/
│   ├── main/kotlin/org/upspa/assetlinks/
│   │   ├── model/
│   │   │   ├── Origin.kt                 # Scheme + Host + Port exact-origin model
│   │   │   ├── CanonicalWebOrigin.kt     # Strictly canonical web origin value class
│   │   │   ├── EnrolledOrigin.kt         # Enrolled DAL principal origin representation
│   │   │   ├── AndroidPackageName.kt     # Validated Android package name value class
│   │   │   ├── CertificateDigest.kt      # Validated SHA-256 hex certificate digest value class
│   │   │   ├── RequestedIdentity.kt      # Verification request identity tuple (origin, package, relation)
│   │   │   ├── AppSigningInfo.kt         # Android app signing info, multi-signer & rotation model
│   │   │   ├── AssetLinkStatement.kt     # Statement list schema
│   │   │   └── Target.kt                 # Statement target (android_app, sha256 fingerprints)
│   │   ├── result/
│   │   │   └── VerificationResult.kt     # Sealed class result hierarchy (Verified, Rejected subtypes)
│   │   ├── crypto/
│   │   │   └── CertificateUtils.kt       # SHA-256 fingerprint computation & hex normalization
│   │   ├── fetcher/
│   │   │   ├── AssetLinkFetcher.kt       # Fetcher interface
│   │   │   └── FakeAssetLinkFetcher.kt   # In-memory, network-isolated fake fetcher for hermetic testing
│   │   ├── verifier/
│   │   │   ├── AssetLinkVerifier.kt      # Public verifier interface with RequestedIdentity overloads
│   │   │   ├── PureAssetLinkVerifier.kt  # Zero-I/O pure verification engine on in-memory evidence
│   │   │   └── UpSpaAssetLinkVerifier.kt # Orchestrator coordinating fetcher and PureAssetLinkVerifier
│   │   └── Main.kt                       # CLI / Demonstration entry point
│   └── test/kotlin/org/upspa/assetlinks/
│       ├── verifier/
│       │   ├── AdversarialNegativeControlTest.kt # 9 explicit fail-closed negative control tests
│       │   └── UpSpaAssetLinkVerifierTest.kt     # Hermetic verifier tests via FakeAssetLinkFetcher
│       ├── model/
│       │   ├── TypedValuesTest.kt        # Unit tests for domain models & value classes
│       │   └── OriginTest.kt             # Exact-origin parsing & canonicalization tests
│       └── crypto/
│           └── CertificateUtilsTest.kt   # Fingerprint normalization tests
```

---

## 🧪 Test Suite & Adversarial Negative Control Mapping

The test suite runs 31 automated tests (`31/31 passed`):

| Test Case | Adversarial Vector / Scenario Tested | Proven Fail-Closed Behavior |
| :--- | :--- | :--- |
| `negative control 1` | **Package name mismatch**: Origin only declares `com.different.app` | Returns `VerificationResult.Rejected.PackageNotFound` |
| `negative control 2` | **Certificate mismatch**: Package matches, but signing digest is unauthorized | Returns `VerificationResult.Rejected.CertificateMismatch` |
| `negative control 3` | **Malformed origin**: Invalid URI format, missing host, or invalid characters | Returns `VerificationResult.Rejected.InvalidOrigin` |
| `negative control 4` | **Non-HTTPS origin**: Insecure `http://` origin supplied | Returns `VerificationResult.Rejected.NonHttpsOrigin` (0 network calls made) |
| `negative control 5` | **Suffix/substring confusion**: Statements contain `com.example.app.evil` | Returns `VerificationResult.Rejected.PackageNotFound` (exact equality enforced) |
| `negative control 6` | **Redirect handling**: HTTP 301/302/307/308 redirect returned | Returns `VerificationResult.Rejected.RedirectAttempted` |
| `negative control 7` | **Missing required relation**: Statement has package/cert, but lacks requested relation | Returns `VerificationResult.Rejected.MissingRequiredRelation` |
| `negative control 8` | **Multiple signers**: App signed by multiple current keys (`hasMultipleSigners=true`) | Returns `VerificationResult.Rejected.MultipleSignersUnsupported` (no crash) |
| `negative control 9` | **Key rotation mismatch**: Key rotation lineage exists, but no historical key matches origin | Returns `VerificationResult.Rejected.CertificateMismatch` |
| `positive control 1` | Single authorized signer | Returns `VerificationResult.Verified` |
| `positive control 2` | APK v3 rotated key where historical certificate matches origin | Returns `VerificationResult.Verified` with matching historical fingerprint |

---

## ⚠️ Known Limitations & Deferred Work (§12)

1. **Proof-of-rotation cryptographic chain**: The engine verifies that an authorized certificate is present in `signingCertificateHistory` independently; full cryptographic signature chaining of certificate N+1 by certificate N is delegated to Android's `PackageManager`.
2. **One-way verification (Web → App)**: The engine verifies that the web origin authorizes the native app. Bidirectional verification (app manifest declaring the web origin) is a recommended future integration.
