package com.upspa.research.provider

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Secure authentication Activity for the locked autofill entry (research topic 8).
 *
 * Launched via the IntentSender attached to the FillResponse. After the user authenticates
 * (BiometricPrompt with device-credential fallback), a simulated derivation delay is applied
 * (modeling TOPRF round-trips) and a dataset of FAKE credentials is returned to the platform
 * through EXTRA_AUTHENTICATION_RESULT.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var bypassButton: Button

    // Single Activity-owned handler so pending derivation callbacks can be cancelled in
    // onDestroy — a Handler created ad hoc in onAuthenticated() would keep a reference to
    // this Activity alive (leak) and could deliver a result after teardown.
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE before setContentView: no frame of the credential UI is ever rendered
        // without screenshot/recording protection. Paired with excludeFromRecents in the
        // manifest so the surface also never appears in the recents screen.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_auth)

        status = findViewById(R.id.authStatus)
        progress = findViewById(R.id.authProgress)
        retryButton = findViewById(R.id.authRetry)
        bypassButton = findViewById(R.id.authBypass)

        retryButton.setOnClickListener {
            retryButton.visibility = View.GONE
            startAuthentication()
        }
        // Emulators in the test matrix often have no lock screen. The bypass keeps experiments
        // reproducible; it is acceptable ONLY because this artifact never touches real secrets.
        bypassButton.setOnClickListener {
            Log.w(TAG, "research bypass used (no device credential enrolled)")
            onAuthenticated()
        }

        startAuthentication()
    }

    private fun startAuthentication() {
        status.text = getString(R.string.auth_status_waiting)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            status.text = getString(R.string.auth_unavailable)
            bypassButton.visibility = View.VISIBLE
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    status.text = errString
                    retryButton.visibility = View.VISIBLE
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.auth_title))
                .setSubtitle(getString(R.string.auth_subtitle))
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun onAuthenticated() {
        LatencyProbe.begin(LatencyProbe.PROBE_AUTH_DERIVE)
        status.text = getString(R.string.auth_status_deriving)
        progress.visibility = View.VISIBLE
        bypassButton.visibility = View.GONE

        val delayMs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            .getLong(Prefs.KEY_LATENCY_MS, 0L)
        mainHandler.postDelayed(::deliverDataset, delayMs)
    }

    override fun onDestroy() {
        // Cancel any pending simulated-derivation callback (see mainHandler comment).
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun deliverDataset() {
        // Lifecycle guard: the delayed derivation may fire while the Activity is already
        // going away (user backed out, screen rotated at the wrong moment). Never deliver
        // a dataset from a dying Activity.
        if (isFinishing || isDestroyed) {
            Log.w(TAG, "deliverDataset skipped: activity is finishing/destroyed")
            return
        }
        val requestingPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: "unknown"
        val ids = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_IDS, AutofillId::class.java)
        val roleOrdinals = intent.getIntArrayExtra(EXTRA_ROLES) ?: IntArray(0)
        if (ids.isNullOrEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val displayName = FakeCredentialFactory.usernameFor(requestingPackage)
        val presentation = RemoteViews(packageName, R.layout.item_credential_entry).apply {
            setTextViewText(R.id.credentialEntryText, getString(R.string.entry_label, displayName))
        }

        @Suppress("DEPRECATION") // RemoteViews presentation keeps the API 26 floor buildable.
        val datasetBuilder = Dataset.Builder()
        val roles = FieldClassifier.Role.entries
        ids.forEachIndexed { index, autofillId ->
            val role = roles.getOrElse(roleOrdinals.getOrElse(index) { -1 }) { FieldClassifier.Role.UNKNOWN }
            @Suppress("DEPRECATION")
            datasetBuilder.setValue(
                autofillId,
                AutofillValue.forText(FakeCredentialFactory.valueFor(requestingPackage, role)),
                presentation,
            )
        }

        val response = FillResponse.Builder().addDataset(datasetBuilder.build()).build()
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        LatencyProbe.end(LatencyProbe.PROBE_AUTH_DERIVE)
        finish()
    }

    companion object {
        private const val TAG = "UpSpaAuth"
        private const val EXTRA_PACKAGE = "com.upspa.research.provider.extra.PACKAGE"
        private const val EXTRA_IDS = "com.upspa.research.provider.extra.IDS"
        private const val EXTRA_ROLES = "com.upspa.research.provider.extra.ROLES"

        fun newIntent(
            context: Context,
            requestingPackage: String,
            fields: List<FieldClassifier.Field>,
        ): Intent = Intent(context, AuthActivity::class.java).apply {
            putExtra(EXTRA_PACKAGE, requestingPackage)
            putParcelableArrayListExtra(EXTRA_IDS, ArrayList(fields.map { it.autofillId }))
            putExtra(EXTRA_ROLES, fields.map { it.role.ordinal }.toIntArray())
        }
    }
}
