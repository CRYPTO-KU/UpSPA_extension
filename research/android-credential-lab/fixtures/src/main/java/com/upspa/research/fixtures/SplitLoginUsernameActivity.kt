package com.upspa.research.fixtures

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button

/** Multi-screen login, step 1: identifier only (research topic 6). */
class SplitLoginUsernameActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_username)

        applySpecHints(R.id.splitUsername to arrayOf(View.AUTOFILL_HINT_USERNAME))

        findViewById<Button>(R.id.splitNext).setOnClickListener {
            startActivity(
                Intent(this, SplitLoginPasswordActivity::class.java)
                    .putExtra(EXTRA_SPEC_HINTS, specHintsRequested),
            )
        }
    }
}
