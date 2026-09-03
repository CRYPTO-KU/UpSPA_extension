package com.upspa.mobile.autofill

import android.view.View
import com.upspa.mobile.autofill.FieldClassifier.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative controls for the classifier's safety guards.
 *
 * A guard that cannot be observed to fail is not evidence of anything. Each test here disables
 * exactly one guard through the test-only [FieldClassifier.Policy] constructor, shows that the
 * unsafe outcome then really does occur, and pins the safe outcome under
 * [FieldClassifier.Policy.DEFAULT] next to it.
 *
 * Production code never builds a weakened policy: `FieldClassifier.classify(AssistStructure)` is
 * the only entry point the service uses and it is hard-wired to the default.
 */
class FieldClassifierNegativeControlTest {
    private val safe = FieldClassifier(FieldClassifier.Policy.DEFAULT)

    /** A negative lookahead on the empty string. It can never match any input. */
    private val noPoisonTerms = Regex("(?!)")

    // --- NC-1: the poison veto ------------------------------------------------------------------

    @Test
    fun `NC-1 default policy refuses a card security code and a search box`() {
        val result = safe.classifyScreen(
            TestNodes.password(
                "poison_card_cvv",
                label = "Card security code",
                inputType = TestNodes.NUMBER_PASSWORD_INPUT,
            ),
            TestNodes.field("search_account", label = "Search accounts"),
        )

        assertEquals(emptyList<String>(), result.fillableKeys())
    }

    @Test
    fun `NC-1 weakening the poison veto lets a card security code be filled`() {
        val weakened = FieldClassifier(FieldClassifier.Policy(poison = noPoisonTerms))

        val result = weakened.classifyScreen(
            TestNodes.password(
                "poison_card_cvv",
                label = "Card security code",
                inputType = TestNodes.NUMBER_PASSWORD_INPUT,
            ),
            TestNodes.field("search_account", label = "Search accounts"),
        )

        assertEquals(
            listOf("poison_card_cvv", "search_account"),
            result.fillableKeys(),
        )
        assertEquals(Role.PASSWORD_CURRENT, result.rolesByKey()["poison_card_cvv"])
        assertEquals(Role.USERNAME, result.rolesByKey()["search_account"])
    }

    @Test
    fun `NC-1 weakening the poison veto also lets topology promote a search box`() {
        val nodes = arrayOf(
            TestNodes.field("store_search", label = "Search"),
            TestNodes.password("field_beta", label = "Value 2"),
        )

        assertEquals(listOf("field_beta"), safe.classifyScreen(*nodes).fillableKeys())

        val weakened = FieldClassifier(FieldClassifier.Policy(poison = noPoisonTerms))
        val result = weakened.classifyScreen(*nodes)

        assertEquals(listOf("store_search", "field_beta"), result.fillableKeys())
        assertEquals(Role.USERNAME, result.rolesByKey()["store_search"])
        assertEquals(3, result.tierOf("store_search"))
    }

    // --- NC-2: the visibility and enabled gates ---------------------------------------------------

    @Test
    fun `NC-2 weakening the visibility and enabled gates exposes unreachable fields`() {
        val nodes = arrayOf(
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
            safe.classifyScreen(*nodes).fillableKeys(),
        )

        val weakened = FieldClassifier(
            FieldClassifier.Policy(enforceVisibilityGate = false, enforceEnabledGate = false),
        )

        assertEquals(
            listOf(
                "hidden_gone_password",
                "hidden_invisible_username",
                "hidden_disabled_password",
                "hidden_visible_username",
                "hidden_visible_password",
            ),
            weakened.classifyScreen(*nodes).fillableKeys(),
        )
    }

    // --- NC-3: the application's own autofill opt-out ---------------------------------------------

    @Test
    fun `NC-3 weakening the importance gate fills a form the application excluded`() {
        val tree = listOf(
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
        )

        assertEquals(emptyList<String>(), safe.classify(tree).fillableKeys())

        val weakened = FieldClassifier(
            FieldClassifier.Policy(enforceImportantForAutofill = false),
        )

        assertEquals(
            listOf("autofill_disabled_username", "autofill_disabled_password"),
            weakened.classify(tree).fillableKeys(),
        )
    }

    // --- NC-4: the pre-authentication lock ----------------------------------------------------------

    @Test
    fun `NC-4 removing the authentication requirement puts values in the pre-auth response`() {
        val result = safe.classifyScreen(
            TestNodes.field("login_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
            TestNodes.password("login_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
        )

        val locked = LockedResponsePolicy.DEFAULT.decide(result, TARGET_PACKAGE)
        assertTrue(locked is LockedResponsePolicy.Decision.LockedEntry)
        assertFalse(
            "a locked decision must not be able to carry a value",
            locked.toString().contains(TEMPLATE_PASSWORD),
        )

        val weakened = LockedResponsePolicy(requireAuthentication = false)
        val unlocked = weakened.decide(result, TARGET_PACKAGE)

        assertTrue(unlocked is LockedResponsePolicy.Decision.UnlockedEntry)
        assertEquals(
            listOf(TEMPLATE_USERNAME, TEMPLATE_PASSWORD),
            (unlocked as LockedResponsePolicy.Decision.UnlockedEntry).values,
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.upspa.mobile.fixtures"

        // Mirrors TemplateCredentialEngine. Both are synthetic markers, never credentials.
        const val TEMPLATE_USERNAME = "template-user"
        const val TEMPLATE_PASSWORD = "UPSPA-TEMPLATE-NOT-A-REAL-CREDENTIAL"
    }
}
