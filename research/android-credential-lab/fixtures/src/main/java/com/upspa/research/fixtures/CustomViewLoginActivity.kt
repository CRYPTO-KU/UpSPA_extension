package com.upspa.research.fixtures

import android.os.Bundle

/** Hosts [CustomLoginView], the virtual-children fixture (research topic 7). */
class CustomViewLoginActivity : FixtureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_view)
    }
}
