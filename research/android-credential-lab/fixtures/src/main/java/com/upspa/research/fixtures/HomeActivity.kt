package com.upspa.research.fixtures

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity

/** Launcher listing every fixture scenario, with a Tier 1 (spec-hints) toggle. */
class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val hintSwitch = findViewById<Switch>(R.id.hintSwitch)

        fun wire(buttonId: Int, target: Class<*>) {
            findViewById<Button>(buttonId).setOnClickListener {
                startActivity(
                    Intent(this, target)
                        .putExtra(FixtureActivity.EXTRA_SPEC_HINTS, hintSwitch.isChecked),
                )
            }
        }

        wire(R.id.openLogin, LoginActivity::class.java)
        wire(R.id.openRegistration, RegistrationActivity::class.java)
        wire(R.id.openPasswordChange, PasswordChangeActivity::class.java)
        wire(R.id.openSplitLogin, SplitLoginUsernameActivity::class.java)
        wire(R.id.openWebView, WebViewLoginActivity::class.java)
        wire(R.id.openComposeLogin, ComposeLoginActivity::class.java)
        wire(R.id.openCustomView, CustomViewLoginActivity::class.java)
        wire(R.id.openNoAutofill, NoAutofillActivity::class.java)
    }
}
