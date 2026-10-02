package com.upspa.mobile.autofill

import android.view.View
import com.upspa.mobile.autofill.FieldClassifier.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regressions for PR #30: use generic ids so metadata, not accidental poison terms, decides. */
class FieldClassifierReviewRegressionTest {
    private val classifier = FieldClassifier(FieldClassifier.Policy.DEFAULT)

    @Test
    fun `email address labels and identifiers remain fillable`() {
        for (label in listOf("Email address", "E-mail address", "EMAIL ADDRESS", "EmailAddress")) {
            val result = classifier.classifyScreen(TestNodes.field("registration_email", label = label))
            assertEquals(label, Role.EMAIL, result.rolesByKey()["registration_email"])
        }
        for (id in listOf("email_address", "emailAddress", "registration_email_address")) {
            assertEquals(id, Role.EMAIL, classifier.classifyScreen(TestNodes.field(id)).rolesByKey()[id])
        }
    }

    @Test
    fun `physical address labels still veto credential hints`() {
        for (label in listOf("Street address", "Postal address", "Shipping address", "Address", "Address line 1")) {
            val result = classifier.classifyScreen(TestNodes.field(
                "field_alpha", label = label, hints = listOf(View.AUTOFILL_HINT_USERNAME),
            ))
            assertEquals(label, emptyList<String>(), result.fillableKeys())
        }
    }

    @Test
    fun `email wording cannot hide a separate physical address signal`() {
        val result = classifier.classifyScreen(TestNodes.field(
            "shipping_address", label = "Email address", hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS),
        ))
        assertEquals(emptyList<String>(), result.fillableKeys())
    }

    @Test
    fun `payment and address platform hints veto masked input`() {
        for (hint in PAYMENT_ADDRESS_PLATFORM_HINTS) {
            val result = classifier.classifyScreen(TestNodes.password("field_alpha", hints = listOf(hint)))
            assertEquals(hint, emptyList<String>(), result.fillableKeys())
        }
    }

    @Test
    fun `payment and address HTML tokens veto masked input`() {
        for (token in PAYMENT_ADDRESS_HTML_TOKENS) {
            val result = classifier.classifyScreen(TestNodes.field(
                "field_alpha", html = mapOf("autocomplete" to token, "type" to "password"),
            ))
            assertEquals(token, emptyList<String>(), result.fillableKeys())
        }
    }

    @Test
    fun `platform deny hint wins regardless of credential hint order`() {
        for (hints in listOf(
            listOf(View.AUTOFILL_HINT_PASSWORD, View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE),
            listOf(View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE, View.AUTOFILL_HINT_PASSWORD),
        )) {
            assertEquals(emptyList<String>(), classifier.classifyScreen(
                TestNodes.password("field_alpha", hints = hints),
            ).fillableKeys())
        }
    }

    @Test
    fun `deny signals win across platform and HTML hints`() {
        val result = classifier.classifyScreen(
            TestNodes.field("field_alpha", hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                html = mapOf("autocomplete" to "section-checkout\tBILLING\nCC-CSC current-password")),
            TestNodes.field("field_beta", hints = listOf(View.AUTOFILL_HINT_POSTAL_ADDRESS),
                html = mapOf("autocomplete" to "username")),
        )
        assertEquals(emptyList<String>(), result.fillableKeys())
    }

    @Test
    fun `topology cannot promote fields with non-credential platform hints`() {
        for (hint in PAYMENT_ADDRESS_PLATFORM_HINTS) {
            val result = classifier.classifyScreen(
                TestNodes.field("field_alpha", hints = listOf(hint)),
                TestNodes.password("field_beta"),
            )
            assertEquals(hint, listOf("field_beta"), result.fillableKeys())
        }
    }

    @Test
    fun `topology cannot promote fields with non-credential HTML tokens`() {
        for (token in PAYMENT_ADDRESS_HTML_TOKENS) {
            val result = classifier.classifyScreen(
                TestNodes.field("field_alpha", html = mapOf("autocomplete" to token)),
                TestNodes.password("field_beta"),
            )
            assertEquals(token, listOf("field_beta"), result.fillableKeys())
        }
    }

    @Test
    fun `sectioned HTML credential tokens support whitespace and case`() {
        val result = classifier.classifyScreen(TestNodes.field(
            "field_alpha", html = mapOf("autocomplete" to "section-login\tBILLING\nEMAIL"),
        ))
        assertEquals(Role.EMAIL, result.rolesByKey()["field_alpha"])
    }

    @Test
    fun `hidden ancestors exclude visible descendants but preserve visible siblings`() {
        for (visibility in listOf(View.GONE, View.INVISIBLE)) {
            val result = classifier.classify(listOf(TestNodes.container(children = listOf(
                TestNodes.container(children = listOf(
                    TestNodes.container(children = listOf(
                        TestNodes.password("hidden_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
                    )),
                )).copy(visibility = visibility),
                TestNodes.field("visible_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
                TestNodes.password("visible_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
            ))))
            assertEquals(listOf("visible_username", "visible_password"), result.fillableKeys())
            assertEquals(listOf("visible_username", "visible_password"), result.fields.map { it.debugKey })
        }
    }

    private companion object {
        // Public Android/AndroidX hint values. Keep the test's cases independent of the deny set.
        val PAYMENT_ADDRESS_PLATFORM_HINTS = listOf(
            "creditCardNumber", "creditCardSecurityCode", "creditCardExpirationDate",
            "creditCardExpirationDay", "creditCardExpirationMonth", "creditCardExpirationYear",
            "postalAddress", "postalCode", "addressCountry", "addressRegion", "addressLocality",
            "streetAddress", "extendedAddress", "extendedPostalCode",
        )
        val PAYMENT_ADDRESS_HTML_TOKENS = listOf(
            "cc-name", "cc-given-name", "cc-additional-name", "cc-family-name", "cc-number",
            "cc-exp", "cc-exp-month", "cc-exp-year", "cc-csc", "cc-type",
            "transaction-currency", "transaction-amount", "street-address", "address-line1",
            "address-line2", "address-line3", "address-level1", "address-level2", "address-level3",
            "address-level4", "country", "country-name", "postal-code",
        )
    }
}
