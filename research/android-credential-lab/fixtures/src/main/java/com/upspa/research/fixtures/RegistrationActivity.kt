package com.upspa.research.fixtures

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.autofill.HintConstants

/** Registration: email + two adjacent new-password fields (Tier 3 topology fixture). */
class RegistrationActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        applySpecHints(
            R.id.regEmail to arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS),
            R.id.regPassword to arrayOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
            R.id.regConfirmPassword to arrayOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
        )

        findViewById<Button>(R.id.regSubmit).setOnClickListener { signalCommit() }
    }
}
