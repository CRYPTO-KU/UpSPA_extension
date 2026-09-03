package com.upspa.research.fixtures

import android.os.Bundle

/** Autofill-disabled fixture (research topic 7); opt-out lives in the layout root. */
class NoAutofillActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_no_autofill)
    }
}
