package com.upspa.research.fixtures

import android.os.Bundle
import android.view.View
import android.widget.Button

/** Multi-screen login, step 2: password only (research topic 6). */
class SplitLoginPasswordActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_password)

        applySpecHints(R.id.splitPassword to arrayOf(View.AUTOFILL_HINT_PASSWORD))

        findViewById<Button>(R.id.splitSubmit).setOnClickListener { signalCommit() }
    }
}
