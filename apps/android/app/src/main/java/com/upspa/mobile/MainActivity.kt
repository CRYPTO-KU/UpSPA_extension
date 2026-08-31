package com.upspa.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootstrapScreen(onEnableAutofill = ::openAutofillSettings)
                }
            }
        }
    }

    private fun openAutofillSettings() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = Uri.parse("package:$packageName")
            },
        )
    }
}

@Composable
private fun BootstrapScreen(onEnableAutofill: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("UpSPA Mobile", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Walking-skeleton build. It has no network permission, does not collect a master " +
                "password, and fills only clearly synthetic template values.",
        )
        Text(
            "Enable the Autofill service, then open the controlled fixture app and focus its " +
                "username or password field.",
        )
        Button(onClick = onEnableAutofill) {
            Text("Enable UpSPA Autofill")
        }
    }
}
