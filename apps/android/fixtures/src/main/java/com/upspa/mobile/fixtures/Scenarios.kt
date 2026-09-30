package com.upspa.mobile.fixtures

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

/** Login form with platform autofill hints on both fields. */
class LoginActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_login
}

/** Registration form: email, a hinted new password, and a hint-free confirmation field. */
class RegistrationActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_registration
}

/** Password change form: a hint-free current password followed by two new-password fields. */
class PasswordChangeActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_password_change
}

/** Step 1 of a split login. Identifier only. */
class SplitUsernameActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_split_username

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<View>(R.id.split_continue).setOnClickListener {
            // Navigation only. The identifier entered in step 1 is never read or carried over.
            startActivity(Intent(this, SplitPasswordActivity::class.java))
        }
    }
}

/**
 * Step 2 of a split login. Password only, with no preceding text field, which is what the
 * topology fallback has to cope with without inventing a username target.
 */
class SplitPasswordActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_split_password
}

/** Legacy form with no autofill hints and no meaningful attribute names. */
class NoHintsLoginActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_no_hints_login
}

/** Gone, invisible, and disabled credential fields alongside two live ones. */
class HiddenAndDisabledActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_hidden_and_disabled
}

/** Payment and search fields that must never be filled, next to a real sign-in box. */
class PoisonFieldsActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_poison_fields
}

/** A form the application has explicitly withheld from autofill services. */
class AutofillDisabledActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_autofill_disabled
}

/** Ordinary, non-credential text fields. */
class UnknownFieldsActivity : FixtureActivity() {
    override val layoutRes = R.layout.activity_unknown_fields
}

/** Menu entries, in the order they are demonstrated and reported. */
val FIXTURE_SCENARIOS: List<Pair<Int, Class<out Activity>>> = listOf(
    R.string.scenario_login to LoginActivity::class.java,
    R.string.scenario_registration to RegistrationActivity::class.java,
    R.string.scenario_password_change to PasswordChangeActivity::class.java,
    R.string.scenario_split_login to SplitUsernameActivity::class.java,
    R.string.scenario_no_hints to NoHintsLoginActivity::class.java,
    R.string.scenario_hidden_disabled to HiddenAndDisabledActivity::class.java,
    R.string.scenario_poison to PoisonFieldsActivity::class.java,
    R.string.scenario_autofill_disabled to AutofillDisabledActivity::class.java,
    R.string.scenario_unknown to UnknownFieldsActivity::class.java,
)
