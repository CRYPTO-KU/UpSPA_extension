package com.upspa.research.fixtures

import android.os.Bundle
import android.view.View
import android.widget.Button

/** Classic single-screen login (research topics 3 and 11). */
class LoginActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        applySpecHints(
            R.id.loginUsername to arrayOf(View.AUTOFILL_HINT_USERNAME),
            R.id.loginPassword to arrayOf(View.AUTOFILL_HINT_PASSWORD),
        )

        findViewById<Button>(R.id.loginSubmit).setOnClickListener { signalCommit() }
    }
}
