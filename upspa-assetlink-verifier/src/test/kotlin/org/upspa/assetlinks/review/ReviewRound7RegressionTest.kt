package org.upspa.assetlinks.review

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.upspa.assetlinks.fetcher.FakeAssetLinkFetcher
import org.upspa.assetlinks.model.AppSigningInfo
import org.upspa.assetlinks.model.Origin
import org.upspa.assetlinks.result.VerificationResult
import org.upspa.assetlinks.verifier.PureAssetLinkVerifier
import org.upspa.assetlinks.verifier.UpSpaAssetLinkVerifier
import java.util.stream.Stream

/**
 * Review round 7 regression tests targeting:
 *
 * Reviewer finding (P2): literal control characters (U+0000-U+001F) inside JSON strings
 * still return Verified instead of InvalidJsonFormat. RFC 8259 section 7 requires these
 * characters to be escaped.
 */
class ReviewRound7RegressionTest {

    companion object {
        private const val HEX_A = "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA"
        private const val HEX_B = "BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB:BB"
        private const val PACKAGE_NAME = "com.example.app"
        private const val ORIGIN_URL = "https://example.com"

        @JvmStatic
        fun controlCharInputs(): Stream<Arguments> = Stream.of(
            Arguments.of("U+0000", "\u0000"),
            Arguments.of("U+0001", "\u0001"),
            Arguments.of("U+0008", "\u0008"),
            Arguments.of("U+0009_tab", "\t"),
            Arguments.of("U+000A_LF", "\n"),
            Arguments.of("U+000C", "\u000C"),
            Arguments.of("U+000D_CR", "\r"),
            Arguments.of("U+001F", "\u001F")
        )

        private fun makeStatementWithSecond(second: String): String {
            return """
                [
                  {
                    "relation": [
                      "delegate_permission/common.handle_all_urls",
                      "$second"
                    ],
                    "target": {
                      "namespace": "android_app",
                      "package_name": "$PACKAGE_NAME",
                      "sha256_cert_fingerprints": [
                        "$HEX_B"
                      ]
                    }
                  }
                ]
            """.trimIndent()
        }
    }

    private val origin = Origin.parse(ORIGIN_URL)
    private val app = AppSigningInfo.fromFingerprints(PACKAGE_NAME, listOf(HEX_B))
    private val pureVerifier = PureAssetLinkVerifier()

    private fun assertInvalidJsonFormatOnBoth(json: String, appSigningInfo: AppSigningInfo = app) {
        val pureResult = pureVerifier.verifyRawJson(origin, appSigningInfo, json)
        assertInstanceOf(
            VerificationResult.Rejected.InvalidJsonFormat::class.java,
            pureResult,
            "Expected InvalidJsonFormat on pureVerifier, got $pureResult"
        )

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val upspaResult = upspaVerifier.verify(origin, appSigningInfo)
        assertInstanceOf(
            VerificationResult.Rejected.InvalidJsonFormat::class.java,
            upspaResult,
            "Expected InvalidJsonFormat on upspaVerifier, got $upspaResult"
        )
    }

    private fun assertVerifiedOnBoth(json: String, appSigningInfo: AppSigningInfo = app) {
        val pureResult = pureVerifier.verifyRawJson(origin, appSigningInfo, json)
        assertInstanceOf(
            VerificationResult.Verified::class.java,
            pureResult,
            "Expected Verified on pureVerifier, got $pureResult"
        )

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val upspaResult = upspaVerifier.verify(origin, appSigningInfo)
        assertInstanceOf(
            VerificationResult.Verified::class.java,
            upspaResult,
            "Expected Verified on upspaVerifier, got $upspaResult"
        )
    }

    // =========================================================================
    // Malformed cases: literal control characters (must fail with InvalidJsonFormat)
    // =========================================================================

    @ParameterizedTest(name = "malformed_literal_control_char_{0}")
    @MethodSource("controlCharInputs")
    @DisplayName("malformed_literal_control_char_in_second_relation_rejected")
    fun `malformed_literal_control_char_in_second_relation_rejected`(@Suppress("UNUSED_PARAMETER") name: String, controlChar: String) {
        val json = makeStatementWithSecond(controlChar)
        assertInvalidJsonFormatOnBoth(json)
    }

    @Test
    @DisplayName("malformed_literal_newline_in_package_name_rejected")
    fun `malformed_literal_newline_in_package_name_rejected`() {
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "com.example\n.app",
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()
        assertInvalidJsonFormatOnBoth(json)
    }

    @Test
    @DisplayName("malformed_literal_newline_in_namespace_rejected")
    fun `malformed_literal_newline_in_namespace_rejected`() {
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_\napp",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()
        assertInvalidJsonFormatOnBoth(json)
    }

    @Test
    @DisplayName("malformed_literal_newline_in_sha256_fingerprint_rejected")
    fun `malformed_literal_newline_in_sha256_fingerprint_rejected`() {
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["${HEX_B.substring(0, 16)}\n${HEX_B.substring(16)}"]
                }
              }
            ]
        """.trimIndent()
        assertInvalidJsonFormatOnBoth(json)
    }

    @Test
    @DisplayName("malformed_literal_newline_in_object_key_rejected")
    fun `malformed_literal_newline_in_object_key_rejected`() {
        val json = """
            [
              {
                "extra\nkey": "value",
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["$HEX_B"]
                }
              }
            ]
        """.trimIndent()
        assertInvalidJsonFormatOnBoth(json)
    }

    @Test
    @DisplayName("malformed_mismatching_signer_with_literal_control_char_rejected_as_invalid_json")
    fun `malformed_mismatching_signer_with_literal_control_char_rejected_as_invalid_json`() {
        // Statement has HEX_A (mismatching, app has HEX_B), but relation contains literal newline
        val json = """
            [
              {
                "relation": [
                  "delegate_permission/common.handle_all_urls",
                  "second\nwith_control"
                ],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()
        val pureResult = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(
            VerificationResult.Rejected.InvalidJsonFormat::class.java,
            pureResult,
            "Expected InvalidJsonFormat on pureVerifier, got $pureResult"
        )
        assertFalse(pureResult is VerificationResult.Rejected.CertificateMismatch)

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val upspaResult = upspaVerifier.verify(origin, app)
        assertInstanceOf(
            VerificationResult.Rejected.InvalidJsonFormat::class.java,
            upspaResult,
            "Expected InvalidJsonFormat on upspaVerifier, got $upspaResult"
        )
        assertFalse(upspaResult is VerificationResult.Rejected.CertificateMismatch)
    }

    // =========================================================================
    // Controls: valid forms that must stay green on both paths
    // =========================================================================

    @Test
    @DisplayName("control1_valid_matching_statement_verifies")
    fun `control1_valid_matching_statement_verifies`() {
        val json = makeStatementWithSecond("valid_second_relation")
        assertVerifiedOnBoth(json)
    }

    @Test
    @DisplayName("control2_valid_mismatching_signer_statement_returns_certificate_mismatch")
    fun `control2_valid_mismatching_signer_statement_returns_certificate_mismatch`() {
        val json = """
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": ["$HEX_A"]
                }
              }
            ]
        """.trimIndent()
        val pureResult = pureVerifier.verifyRawJson(origin, app, json)
        assertInstanceOf(
            VerificationResult.Rejected.CertificateMismatch::class.java,
            pureResult,
            "Expected CertificateMismatch on pureVerifier, got $pureResult"
        )

        val upspaVerifier = UpSpaAssetLinkVerifier(fetcher = FakeAssetLinkFetcher.withJson(origin, json))
        val upspaResult = upspaVerifier.verify(origin, app)
        assertInstanceOf(
            VerificationResult.Rejected.CertificateMismatch::class.java,
            upspaResult,
            "Expected CertificateMismatch on upspaVerifier, got $upspaResult"
        )
    }

    @ParameterizedTest(name = "control3_escaped_form_{0}")
    @ValueSource(strings = [
        "\\n",
        "\\t",
        "\\r",
        "\\u0000",
        "\\u001f",
        "\\\"",
        "\\\\"
    ])
    @DisplayName("control3_escaped_forms_in_second_relation_verify")
    fun `control3_escaped_forms_in_second_relation_verify`(escaped: String) {
        val json = makeStatementWithSecond("escaped_$escaped")
        assertVerifiedOnBoth(json)
    }

    @Test
    @DisplayName("control4_whitespace_between_tokens_verifies")
    fun `control4_whitespace_between_tokens_verifies`() {
        val json = "\r\n\t [ \r\n\t { \r\n\t \"relation\" : \r\n\t [ \r\n\t \"delegate_permission/common.handle_all_urls\" \r\n\t ] , \r\n\t \"target\" : \r\n\t { \r\n\t \"namespace\" : \r\n\t \"android_app\" , \r\n\t \"package_name\" : \r\n\t \"$PACKAGE_NAME\" , \r\n\t \"sha256_cert_fingerprints\" : \r\n\t [ \r\n\t \"$HEX_B\" \r\n\t ] \r\n\t } \r\n\t } \r\n\t ] \r\n\t "
        assertVerifiedOnBoth(json)
    }

    @Test
    @DisplayName("control5_string_ending_with_escaped_backslash_verifies")
    fun `control5_string_ending_with_escaped_backslash_verifies`() {
        val json = """
            [
              {
                "relation": [
                  "delegate_permission/common.handle_all_urls",
                  "abc\\"
                ],
                "target": {
                  "namespace": "android_app",
                  "package_name": "$PACKAGE_NAME",
                  "sha256_cert_fingerprints": [
                    "$HEX_B"
                  ]
                }
              }
            ]
        """.trimIndent()
        assertVerifiedOnBoth(json)
    }

    @Test
    @DisplayName("control6_literal_del_and_non_ascii_characters_verify")
    fun `control6_literal_del_and_non_ascii_characters_verify`() {
        val delAndNonAscii = "test_\u007F_ğ"
        val json = makeStatementWithSecond(delAndNonAscii)
        assertVerifiedOnBoth(json)
    }
}
