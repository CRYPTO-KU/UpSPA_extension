package com.upspa.mobile.fixture.negative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class CredentialUnlockOnlyRecentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setRecentsScreenshotEnabled(false)
        setContent {
            Text("Enter your master password to continue")
        }
    }
}
