package com.upspa.mobile.autofill

import android.view.View
import androidx.autofill.HintConstants
import com.upspa.mobile.autofill.FieldClassifier.Role
import com.upspa.mobile.autofill.FieldClassifier.ScreenIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the default classifier policy.
 *
 * Each screen here mirrors one Activity in the `:fixtures` module, so a failure names both the
 * rule that broke and the fixture that will show it on a device. The matching negative controls
 * live in [FieldClassifierNegativeControlTest].
 */
class FieldClassifierTest {
    private val classifier = FieldClassifier(FieldClassifier.Policy.DEFAULT)

    // --- Tier 1: platform autofill hints -------------------------------------------------------

    @Test
    fun `login screen resolves both fields from platform hints`() {
        val result = classifier.classifyScreen(
            TestNodes.field("login_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            TestNodes.password("login_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        assertEquals(
            mapOf("login_username" to Role.USERNAME, "login_password" to Role.PASSWORD_CURRENT),
            result.rolesByKey(),
        )
        assertEquals(1, result.tierOf("login_username"))
        assertEquals(1, result.tierOf("login_password"))
        assertEquals(ScreenIntent.LOGIN, result.intent)
    }

    @Test
    fun `every supported platform hint maps to its role`() {
        val result = classifier.classifyScreen(
            TestNodes.field("email_field", hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)),
            TestNodes.field(
                "new_username_field",
                hints = listOf(HintConstants.AUTOFILL_HINT_NEW_USERNAME),
            ),
            TestNodes.password(
                "new_password_field",
                hints = listOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
            ),
            TestNodes.field("otp_field", hints = listOf(HintConstants.AUTOFILL_HINT_SMS_OTP)),
        )

        assertEquals(
            mapOf(
                "email_field" to Role.EMAIL,
                "new_username_field" to Role.USERNAME,
                "new_password_field" to Role.PASSWORD_NEW,
                "otp_field" to Role.OTP,
            ),
            result.rolesByKey(),
        )
        assertTrue(result.fields.all { it.tier == 1 })
    }

    @Test
    fun `an unrecognised hint does not become a fill target on its own`() {
        val result = classifier.classifyScreen(
            TestNodes.field("mystery_field", hints = listOf("upspaUnknownHint")),
        )

        assertEquals(emptyList<String>(), result.fillableKeys())
        assertEquals(ScreenIntent.UNKNOWN, result.intent)
    }

    // --- Tier 1: HTML autocomplete (WebView nodes) ---------------------------------------------

    @Test
    fun `html autocomplete tokens are honoured`() {
        val result = classifier.classifyScreen(
            TestNodes.field("web_user", html = mapOf("autocomplete" to "username")),
            TestNodes.field(
                "web_current",
                html = mapOf("autocomplete" to "current-password", "type" to "password"),
            ),
            TestNodes.field(
                "web_new",
                html = mapOf("autocomplete" to "new-password", "type" to "password"),
            ),
            TestNodes.field("web_otp", html = mapOf("autocomplete" to "one-time-code")),
        )

        assertEquals(
            mapOf(
                "web_user" to Role.USERNAME,
                "web_current" to Role.PASSWORD_CURRENT,
                "web_new" to Role.PASSWORD_NEW,
                "web_otp" to Role.OTP,
            ),
            result.rolesByKey(),
        )
        assertTrue(result.fields.all { it.tier == 1 })
    }

    @Test
    fun `a sectioned autocomplete value still resolves`() {
        val result = classifier.classifyScreen(
            TestNodes.field("web_user", html = mapOf("autocomplete" to "section-blue billing username")),
        )

        assertEquals(Role.USERNAME, result.rolesByKey()["web_user"])
        assertEquals(1, result.tierOf("web_user"))
    }

    @Test
    fun `an html password type is treated as a password input`() {
        val result = classifier.classifyScreen(
            TestNodes.field("web_pass", label = "Password", html = mapOf("type" to "password")),
        )

        assertEquals(Role.PASSWORD_CURRENT, result.rolesByKey()["web_pass"])
        assertEquals(2, result.tierOf("web_pass"))
    }

    // --- Tier 2: attribute corpus ---------------------------------------------------------------

    @Test
    fun `registration screen resolves the hint-free confirmation field`() {
        val result = classifier.classifyScreen(
            TestNodes.field("registration_email", hints = listOf(View.AUTOFILL_HINT_EMAIL_ADDRESS)),
            TestNodes.password(
                "registration_new_password",
                hints = listOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
            ),
            TestNodes.password("registration_confirm_password", label = "Confirm password"),
        )

        assertEquals(
            mapOf(
                "registration_email" to Role.EMAIL,
                "registration_new_password" to Role.PASSWORD_NEW,
                "registration_confirm_password" to Role.PASSWORD_NEW,
            ),
            result.rolesByKey(),
        )
        assertEquals(ScreenIntent.REGISTRATION, result.intent)
    }

    @Test
    fun `password change screen separates the current password from the new one`() {
        val result = classifier.classifyScreen(
            TestNodes.password("password_change_current", label = "Current password"),
            TestNodes.password(
                "password_change_new",
                hints = listOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
            ),
            TestNodes.password("password_change_confirm", label = "Confirm new password"),
        )

        assertEquals(
            mapOf(
                "password_change_current" to Role.PASSWORD_CURRENT,
                "password_change_new" to Role.PASSWORD_NEW,
                "password_change_confirm" to Role.PASSWORD_NEW,
            ),
            result.rolesByKey(),
        )
        assertEquals(ScreenIntent.PASSWORD_CHANGE, result.intent)
    }

    @Test
    fun `an identifier is recognised from a content description alone`() {
        val result = classifier.classifyScreen(
            TestNodes.field("field_7", contentDescription = "Account name"),
        )

        assertEquals(Role.USERNAME, result.rolesByKey()["field_7"])
        assertEquals(2, result.tierOf("field_7"))
    }

    // --- Poison veto ----------------------------------------------------------------------------

    @Test
    fun `checkout fields are refused while the sign-in box is still offered`() {
        val result = classifier.classifyScreen(
            TestNodes.field("poison_search_query", label = "Search this store"),
            TestNodes.field("poison_coupon_code", label = "Coupon code"),
            TestNodes.field(
                "poison_card_number",
                label = "Card number",
                inputType = TestNodes.NUMBER_INPUT,
            ),
            TestNodes.password(
                "poison_card_cvv",
                label = "Card security code",
                inputType = TestNodes.NUMBER_PASSWORD_INPUT,
            ),
            TestNodes.field("poison_postal_code", label = "Postal code"),
            TestNodes.field("poison_login_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            TestNodes.password("poison_login_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        assertEquals(
            listOf("poison_login_username", "poison_login_password"),
            result.fillableKeys(),
        )
        assertEquals(ScreenIntent.LOGIN, result.intent)
    }

    @Test
    fun `a masked payment field is refused even though it looks like a password`() {
        val result = classifier.classifyScreen(
            TestNodes.password(
                "card_cvc",
                label = "Security code",
                inputType = TestNodes.NUMBER_PASSWORD_INPUT,
            ),
        )

        assertEquals(emptyList<String>(), result.fillableKeys())
    }

    @Test
    fun `a poison term outranks an identifier term in the same corpus`() {
        val result = classifier.classifyScreen(
            TestNodes.field("search_username", label = "Search users"),
        )

        assertEquals(Role.UNKNOWN, result.rolesByKey()["search_username"])
    }

    @Test
    fun `an explicit hint still wins over a poison term`() {
        // Tier 1 is a deliberate statement by the application, so it is trusted over a name that
        // happens to contain a poison term. Recorded here so the precedence cannot drift silently.
        val result = classifier.classifyScreen(
            TestNodes.field("account_search_box", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
        )

        assertEquals(Role.USERNAME, result.rolesByKey()["account_search_box"])
        assertEquals(1, result.tierOf("account_search_box"))
    }

    // --- Collection gate --------------------------------------------------------------------------

    @Test
    fun `gone invisible and disabled fields never reach the classifier`() {
        val result = classifier.classifyScreen(
            TestNodes.password(
                "hidden_gone_password",
                hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                visibility = View.GONE,
            ),
            TestNodes.field(
                "hidden_invisible_username",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                visibility = View.INVISIBLE,
            ),
            TestNodes.password(
                "hidden_disabled_password",
                hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                enabled = false,
            ),
            TestNodes.field("hidden_visible_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            TestNodes.password("hidden_visible_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        assertEquals(
            listOf("hidden_visible_username", "hidden_visible_password"),
            result.fields.map { it.debugKey },
        )
        assertEquals(2, result.fillable.size)
    }

    @Test
    fun `a node without an autofill id is skipped`() {
        val result = classifier.classifyScreen(
            TestNodes.field(
                "unaddressable_username",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                hasAutofillId = false,
            ),
        )

        assertEquals(emptyList<String>(), result.fields.map { it.debugKey })
    }

    @Test
    fun `a non-text autofill type is skipped`() {
        val result = classifier.classifyScreen(
            TestNodes.field(
                "username_spinner",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                autofillType = View.AUTOFILL_TYPE_LIST,
            ),
        )

        assertEquals(emptyList<String>(), result.fields.map { it.debugKey })
    }

    // --- importantForAutofill ---------------------------------------------------------------------

    @Test
    fun `a field marked not important for autofill is skipped`() {
        val result = classifier.classifyScreen(
            TestNodes.field(
                "opted_out_username",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO,
            ),
        )

        assertEquals(emptyList<String>(), result.fields.map { it.debugKey })
    }

    @Test
    fun `a noExcludeDescendants container hides its whole subtree`() {
        val result = classifier.classify(
            listOf(
                TestNodes.container(
                    id = "autofill_disabled_root",
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    children = listOf(
                        TestNodes.field(
                            "autofill_disabled_username",
                            hints = listOf(View.AUTOFILL_HINT_USERNAME),
                        ),
                        TestNodes.password(
                            "autofill_disabled_password",
                            hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(emptyList<String>(), result.fields.map { it.debugKey })
        assertEquals(ScreenIntent.UNKNOWN, result.intent)
    }

    @Test
    fun `a yesExcludeDescendants container hides its children only`() {
        val result = classifier.classify(
            listOf(
                TestNodes.container(
                    id = "root",
                    children = listOf(
                        TestNodes.field("outer_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
                        TestNodes.container(
                            id = "sealed_group",
                            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS,
                            children = listOf(
                                TestNodes.password(
                                    "sealed_password",
                                    hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("outer_username"), result.fields.map { it.debugKey })
    }

    @Test
    fun `an unreported importance is treated as auto, which is what API 26 and 27 send`() {
        val result = classifier.classifyScreen(
            TestNodes.field(
                "login_username",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                importantForAutofill = null,
            ),
        )

        assertEquals(listOf("login_username"), result.fillableKeys())
    }

    // --- Tier 3: topology --------------------------------------------------------------------------

    @Test
    fun `an unlabelled field before a lone password is promoted to username`() {
        val result = classifier.classifyScreen(
            TestNodes.field("field_alpha", label = "Value 1"),
            TestNodes.password("field_beta", label = "Value 2"),
        )

        assertEquals(
            mapOf("field_alpha" to Role.USERNAME, "field_beta" to Role.PASSWORD_CURRENT),
            result.rolesByKey(),
        )
        assertEquals(3, result.tierOf("field_alpha"))
        assertEquals(2, result.tierOf("field_beta"))
        assertEquals(ScreenIntent.LOGIN, result.intent)
    }

    @Test
    fun `a password-only split login does not invent a username target`() {
        val result = classifier.classifyScreen(
            TestNodes.password("split_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        assertEquals(listOf("split_password"), result.fillableKeys())
        assertEquals(ScreenIntent.LOGIN, result.intent)
    }

    @Test
    fun `an identifier-only split login is still a login screen`() {
        val result = classifier.classifyScreen(
            TestNodes.field("split_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
        )

        assertEquals(listOf("split_username"), result.fillableKeys())
        assertEquals(ScreenIntent.LOGIN, result.intent)
    }

    @Test
    fun `the topology fallback refuses to promote a poison field`() {
        val result = classifier.classifyScreen(
            TestNodes.field("store_search", label = "Search"),
            TestNodes.password("field_beta", label = "Value 2"),
        )

        assertEquals(listOf("field_beta"), result.fillableKeys())
    }

    @Test
    fun `two hint-free passwords with no current marker are read as a registration`() {
        val result = classifier.classifyScreen(
            TestNodes.password("password_one", label = "Password"),
            TestNodes.password("password_two", label = "Repeat password"),
        )

        assertEquals(
            mapOf("password_one" to Role.PASSWORD_NEW, "password_two" to Role.PASSWORD_NEW),
            result.rolesByKey(),
        )
        assertEquals(ScreenIntent.REGISTRATION, result.intent)
    }

    // --- Unknown screens ----------------------------------------------------------------------------

    @Test
    fun `an ordinary preferences screen produces nothing to fill`() {
        val result = classifier.classifyScreen(
            TestNodes.field("preference_favourite_colour", label = "Favourite colour"),
            TestNodes.field("preference_pet", label = "Pet"),
            TestNodes.field(
                "preference_lucky_number",
                label = "Lucky number",
                inputType = TestNodes.NUMBER_INPUT,
            ),
        )

        assertEquals(emptyList<String>(), result.fillableKeys())
        assertEquals(ScreenIntent.UNKNOWN, result.intent)
    }

    @Test
    fun `an empty screen produces nothing to fill`() {
        val result = classifier.classify(emptyList())

        assertEquals(emptyList<String>(), result.fillableKeys())
        assertEquals(ScreenIntent.UNKNOWN, result.intent)
    }

    // --- Logging safety --------------------------------------------------------------------------------

    @Test
    fun `the safe summary exposes roles and tiers but no attribute text`() {
        val marker = "UPSPA-SYNTHETIC-CORPUS-MARKER"
        val result = classifier.classifyScreen(
            TestNodes.field(
                "login_username",
                hints = listOf(View.AUTOFILL_HINT_USERNAME),
                label = marker,
            ),
            TestNodes.password("login_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        val summary = result.safeSummary()
        assertEquals("USERNAME:T1,PASSWORD_CURRENT:T1", summary)
        assertFalse(summary.contains(marker))
        assertFalse(summary.contains("login_username"))
    }
}
