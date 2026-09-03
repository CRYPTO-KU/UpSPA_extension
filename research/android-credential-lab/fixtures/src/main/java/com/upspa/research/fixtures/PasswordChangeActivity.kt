package com.upspa.research.fixtures

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.autofill.HintConstants

/** Password change: current + new + confirm (Tier 3 topology fixture). */
class PasswordChangeActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_change)

        applySpecHints(
            R.id.pcCurrentPassword to arrayOf(View.AUTOFILL_HINT_PASSWORD),
            R.id.pcNewPassword to arrayOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
            R.id.pcConfirmPassword to arrayOf(HintConstants.AUTOFILL_HINT_NEW_PASSWORD),
        )

        findViewById<Button>(R.id.pcSubmit).setOnClickListener { signalCommit() }
    }
}
