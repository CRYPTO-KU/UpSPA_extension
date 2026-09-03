package com.upspa.mobile.autofill

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response UpSPA is allowed to return before the user has unlocked it.
 *
 * The requirement is that a fill request produces one generic entry and nothing else: no dataset,
 * no value, and no hint about which account might be behind it. The corresponding negative control
 * is `NC-4` in [FieldClassifierNegativeControlTest].
 */
class LockedResponsePolicyTest {
    private val classifier = FieldClassifier(FieldClassifier.Policy.DEFAULT)

    private fun loginScreen() = classifier.classifyScreen(
        TestNodes.field("login_username", hints = listOf(View.AUTOFILL_HINT_USERNAME)),
        TestNodes.password("login_password", hints = listOf(View.AUTOFILL_HINT_PASSWORD)),
    )

    @Test
    fun `a classified login yields a single locked entry covering every fillable field`() {
        val decision = LockedResponsePolicy.DEFAULT.decide(loginScreen(), TARGET_PACKAGE)

        assertTrue(decision is LockedResponsePolicy.Decision.LockedEntry)
        assertEquals(
            listOf("login_username", "login_password"),
            (decision as LockedResponsePolicy.Decision.LockedEntry).fields.map { it.debugKey },
        )
    }

    @Test
    fun `the locked entry carries no synthetic or template value`() {
        val decision = LockedResponsePolicy.DEFAULT.decide(loginScreen(), TARGET_PACKAGE)

        val rendered = decision.toString()
        assertFalse(rendered.contains("UPSPA-TEMPLATE"))
        assertFalse(rendered.contains("template-user"))
        assertFalse(rendered.contains("@example.invalid"))
    }

    @Test
    fun `a screen with nothing to fill yields no response at all`() {
        val nothing = classifier.classifyScreen(
            TestNodes.field("preference_favourite_colour", label = "Favourite colour"),
        )

        assertEquals(
            LockedResponsePolicy.Decision.None,
            LockedResponsePolicy.DEFAULT.decide(nothing, TARGET_PACKAGE),
        )
    }

    @Test
    fun `a request without a requesting package yields no response at all`() {
        assertEquals(
            LockedResponsePolicy.Decision.None,
            LockedResponsePolicy.DEFAULT.decide(loginScreen(), null),
        )
        assertEquals(
            LockedResponsePolicy.Decision.None,
            LockedResponsePolicy.DEFAULT.decide(loginScreen(), "   "),
        )
    }

    @Test
    fun `an application that opted out of autofill yields no response at all`() {
        val excluded = classifier.classify(
            listOf(
                TestNodes.container(
                    id = "autofill_disabled_root",
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                    children = listOf(
                        TestNodes.field(
                            "autofill_disabled_username",
                            hints = listOf(View.AUTOFILL_HINT_USERNAME),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            LockedResponsePolicy.Decision.None,
            LockedResponsePolicy.DEFAULT.decide(excluded, TARGET_PACKAGE),
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.upspa.mobile.fixtures"
    }
}
