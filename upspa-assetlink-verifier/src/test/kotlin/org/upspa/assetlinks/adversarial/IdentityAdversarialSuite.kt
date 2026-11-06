package org.upspa.assetlinks.adversarial

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AssetLinkStatement
import org.upspa.assetlinks.model.RequestedIdentity
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.PureAssetLinkVerifier
import org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifier
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream
import kotlin.reflect.KClass

class IdentityAdversarialSuite {

    data class MatrixRow(
        val caseId: String,
        val category: String,
        val expected: String,
        val actual: String,
        val entryPoints: String,
        val status: String
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("provideCases")
    fun testIdentityCase(caseId: String, case: IdentityCase) {
        var actualSummary = "UNKNOWN"
        var entryPointsUsed = "None"
        var passed = false

        try {
            when (val expected = case.expected) {
                is Expected.ConstructionRejected -> {
                    entryPointsUsed = "Construction"
                    assertThrows(expected.expectedException.java) {
                        case.build()
                    }
                    actualSummary = "ConstructionRejected(${expected.expectedException.simpleName})"
                    passed = true
                }
                is Expected.Verified -> {
                    val input = case.build()
                    val pureVerifier = PureAssetLinkVerifier()
                    val pureResult = pureVerifier.verify(input.requestedOrigin, input.appSigningInfo, input.evidence)

                    assertTrue(
                        pureResult is VerificationResult.Verified,
                        "Expected Verified for $caseId, got $pureResult"
                    )
                    val verifiedPure = pureResult as VerificationResult.Verified
                    expected.assertFields?.invoke(verifiedPure)

                    if (case.bothEntryPoints) {
                        entryPointsUsed = "Pure + UpSpa(FakeFetcher)"
                        val json = statementsAdapter.toJson(input.evidence.statements)
                        val fetcher = FakeAssetLinkFetcher.withJson(input.evidence.sourceOrigin, json)
                        val upSpaVerifier = UpSpaAssetLinkVerifier(fetcher)
                        val upSpaResult = upSpaVerifier.verify(input.requestedOrigin, input.appSigningInfo)

                        assertEquals(
                            pureResult::class,
                            upSpaResult::class,
                            "Mismatch between pure and UpSpa verifiers for $caseId"
                        )
                        val verifiedUpSpa = upSpaResult as VerificationResult.Verified
                        assertEquals(verifiedPure.origin, verifiedUpSpa.origin)
                        assertEquals(verifiedPure.packageName, verifiedUpSpa.packageName)
                        assertEquals(verifiedPure.matchedFingerprint, verifiedUpSpa.matchedFingerprint)
                        assertEquals(verifiedPure.relations, verifiedUpSpa.relations)
                    } else {
                        entryPointsUsed = "Pure"
                    }
                    actualSummary = "Verified"
                    passed = true
                }
                is Expected.Rejected -> {
                    val input = case.build()
                    val pureVerifier = PureAssetLinkVerifier()
                    val pureResult = pureVerifier.verify(input.requestedOrigin, input.appSigningInfo, input.evidence)

                    assertTrue(
                        pureResult is VerificationResult.Rejected,
                        "Expected Rejected for $caseId, got $pureResult"
                    )
                    val rejectedPure = pureResult as VerificationResult.Rejected
                    assertEquals(
                        expected.expectedClass,
                        rejectedPure::class,
                        "Rejection subtype mismatch for $caseId"
                    )
                    expected.assertFields?.invoke(rejectedPure)

                    if (case.bothEntryPoints) {
                        entryPointsUsed = "Pure + UpSpa(FakeFetcher)"
                        val json = statementsAdapter.toJson(input.evidence.statements)
                        val fetcher = FakeAssetLinkFetcher.withJson(input.evidence.sourceOrigin, json)
                        val upSpaVerifier = UpSpaAssetLinkVerifier(fetcher)
                        val upSpaResult = upSpaVerifier.verify(input.requestedOrigin, input.appSigningInfo)

                        assertEquals(
                            pureResult::class,
                            upSpaResult::class,
                            "Mismatch between pure and UpSpa verifiers for $caseId"
                        )
                    } else {
                        entryPointsUsed = "Pure"
                    }
                    actualSummary = "Rejected(${pureResult::class.simpleName})"
                    passed = true
                }
            }
        } finally {
            MATRIX_RECORDS[caseId] = MatrixRow(
                caseId = caseId,
                category = case.category.name,
                expected = when (case.expected) {
                    is Expected.Verified -> "Verified"
                    is Expected.Rejected -> "Rejected(${case.expected.expectedClass.simpleName})"
                    is Expected.ConstructionRejected -> "ConstructionRejected(${case.expected.expectedException.simpleName})"
                },
                actual = actualSummary,
                entryPoints = entryPointsUsed,
                status = if (passed) "PASS" else "FAIL"
            )
        }
    }

    @Test
    fun `meta-test case IDs unique and categories have at least 3 cases`() {
        val cases = IdentityCaseCatalog.ALL_CASES

        // Assert unique case IDs
        val ids = cases.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "Duplicate case IDs found: $duplicates")

        // Assert at least 45 cases
        assertTrue(cases.size >= 45, "Suite must contain at least 45 cases, found: ${cases.size}")

        // Assert >= 3 cases per category
        for (category in Category.values()) {
            val count = cases.count { it.category == category }
            assertTrue(
                count >= 3,
                "Category $category must have at least 3 cases, found: $count"
            )
        }
    }

    @Test
    fun `meta-test reachable rejection coverage and allow-list assert against reality`() {
        val allRejectedSubclasses: Set<KClass<out VerificationResult.Rejected>> =
            VerificationResult.Rejected::class.sealedSubclasses.toSet()

        // 1. Assert allow-list validity against reality
        val allowListClasses = UNREACHABLE_OR_FETCHER_ONLY_ALLOW_LIST.keys
        for (allowClass in allowListClasses) {
            assertTrue(
                allowClass in allRejectedSubclasses,
                "Allow-listed class ${allowClass.simpleName} does not exist in VerificationResult.Rejected hierarchy"
            )
        }

        // 2. Determine which rejection subclasses are produced by the cases
        val producedClasses = IdentityCaseCatalog.ALL_CASES.mapNotNull { case ->
            when (val exp = case.expected) {
                is Expected.Rejected -> exp.expectedClass
                else -> null
            }
        }.toSet()

        // 3. Assert no allow-listed class is produced (proves allow-list is not stale)
        for (allowClass in allowListClasses) {
            assertFalse(
                allowClass in producedClasses,
                "Stale allow-list entry: ${allowClass.simpleName} is produced by a case and is thus reachable"
            )
        }

        // 4. Assert every reachable rejection class is covered
        val expectedReachable = allRejectedSubclasses - allowListClasses
        val missingClasses = expectedReachable - producedClasses
        assertTrue(
            missingClasses.isEmpty(),
            "Reachable rejection subclasses missing from adversarial case suite: ${missingClasses.map { it.simpleName }}"
        )
    }

    companion object {
        private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        private val statementsAdapter: JsonAdapter<List<AssetLinkStatement>> =
            moshi.adapter(Types.newParameterizedType(List::class.java, AssetLinkStatement::class.java))

        private val MATRIX_RECORDS = ConcurrentHashMap<String, MatrixRow>()

        /**
         * Allow-list of unreachable or fetcher-layer-only rejection subtypes.
         * Must be asserted against reality so stale entries fail the meta-test.
         */
        val UNREACHABLE_OR_FETCHER_ONLY_ALLOW_LIST: Map<KClass<out VerificationResult.Rejected>, String> = mapOf(
            VerificationResult.Rejected.RedirectAttempted::class to
                    "Fetch-layer only; emitted exclusively by HTTP client when encountering 301/302/307/308 redirect",
            VerificationResult.Rejected.FetchFailed::class to
                    "Fetch-layer only; emitted exclusively by HTTP client on non-200 HTTP server response",
            VerificationResult.Rejected.NetworkError::class to
                    "Fetch-layer only; emitted exclusively by HTTP client on network, socket, or TLS handshake failure",
            VerificationResult.Rejected.InvalidContentType::class to
                    "Fetch-layer only; emitted exclusively by HTTP client when Content-Type header is not application/json",
            VerificationResult.Rejected.NoSigningCertificatesFound::class to
                    "Structurally unreachable behind AppSigningInfo single-family construction invariant (non-empty certs required)",
            VerificationResult.Rejected.InvalidJsonFormat::class to
                    "Raw-JSON parsing entry point only (verifyRawJson); unreachable via typed AssetLinkEvidence public API",
            VerificationResult.Rejected.InvalidOrigin::class to
                    "Raw-string origin URL entry point only; unreachable via strongly-typed Origin instances",
            VerificationResult.Rejected.RequestedPackageMismatch::class to
                    "Produced only by AssetLinkVerifier.verify(RequestedIdentity, ...), which this (origin, evidence) case table does not drive; covered by ReviewRound5TypedOutcomeTest"
        )

        @JvmStatic
        fun provideCases(): Stream<Arguments> {
            return IdentityCaseCatalog.ALL_CASES.stream().map { case ->
                Arguments.of(case.id, case)
            }
        }

        @JvmStatic
        @AfterAll
        fun writeReportMatrix() {
            if (MATRIX_RECORDS.isEmpty()) return
            val reportDir = File("build/reports")
            reportDir.mkdirs()
            val reportFile = File(reportDir, "identity-adversarial-matrix.md")

            val sb = java.lang.StringBuilder()
            sb.append("# Identity Adversarial Test Matrix\n\n")
            sb.append("| Case ID | Category | Expected | Actual | Entry Points | Status |\n")
            sb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n")

            val sortedRows = MATRIX_RECORDS.values.sortedBy { it.caseId }
            for (row in sortedRows) {
                sb.append("| `${row.caseId}` | `${row.category}` | `${row.expected}` | `${row.actual}` | ${row.entryPoints} | **${row.status}** |\n")
            }

            reportFile.writeText(sb.toString(), Charsets.UTF_8)
            println("Adversarial matrix generated at: ${reportFile.absolutePath}")
        }
    }
}
