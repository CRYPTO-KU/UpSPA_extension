package com.upspa.research.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Status and settings screen for the research provider. Also registered as the autofill
 * service's settingsActivity (see res/xml/autofill_service_config.xml).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var serviceStatus: TextView
    private lateinit var latencyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.serviceStatus)
        latencyInput = findViewById(R.id.latencyInput)

        findViewById<Button>(R.id.enableAutofill).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        val prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        latencyInput.setText(prefs.getLong(Prefs.KEY_LATENCY_MS, 0L).toString())
        findViewById<Button>(R.id.saveLatency).setOnClickListener {
            val latencyMs = latencyInput.text.toString().toLongOrNull() ?: 0L
            prefs.edit().putLong(Prefs.KEY_LATENCY_MS, latencyMs).apply()
            Toast.makeText(this, "Simulated latency: ${latencyMs}ms", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val autofillManager = getSystemService(AutofillManager::class.java)
        serviceStatus.text = when {
            autofillManager == null || !autofillManager.isAutofillSupported ->
                "Autofill: NOT SUPPORTED on this device"
            autofillManager.hasEnabledAutofillServices() ->
                "Autofill: ENABLED (this app is the active service)"
            else ->
                "Autofill: available, but this app is not the active service"
        }
    }
}
