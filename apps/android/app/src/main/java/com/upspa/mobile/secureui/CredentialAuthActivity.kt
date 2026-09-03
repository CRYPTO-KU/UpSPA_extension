package com.upspa.mobile.secureui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.upspa.mobile.R
import com.upspa.mobile.autofill.FieldClassifier
import com.upspa.mobile.engine.TemplateCredentialEngine

class CredentialAuthActivity : ComponentActivity() {
    private val targetPackage: String? by lazy { intent.getStringExtra(EXTRA_TARGET_PACKAGE) }
    private val requestToken: String? by lazy { intent.getStringExtra(EXTRA_REQUEST_TOKEN) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val target = targetPackage
        val token = requestToken
        if (target.isNullOrBlank() || token.isNullOrBlank()) {
            cancelAndFinish()
            return
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("UpSPA template unlock", style = MaterialTheme.typography.headlineMedium)
                        Text("Requesting package: $target")
                        Text(
                            "No password is collected in this bootstrap. Continuing returns a " +
                                "synthetic value only to prove the Autofill lifecycle.",
                        )
                        Button(onClick = ::deliverTemplateDataset) {
                            Text("Continue with template credential")
                        }
                        Button(onClick = ::cancelAndFinish) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    private fun deliverTemplateDataset() {
        val ids = parcelableIds()
        val roleOrdinals = intent.getIntArrayExtra(EXTRA_ROLES) ?: intArrayOf()
        if (ids.isEmpty() || ids.size != roleOrdinals.size) {
            cancelAndFinish()
            return
        }

        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, getString(R.string.template_entry))
        }

        @Suppress("DEPRECATION")
        val dataset = Dataset.Builder()
        val roles = FieldClassifier.Role.entries
        ids.forEachIndexed { index, autofillId ->
            val role = roles.getOrNull(roleOrdinals[index]) ?: FieldClassifier.Role.UNKNOWN
            @Suppress("DEPRECATION")
            dataset.setValue(
                autofillId,
                AutofillValue.forText(TemplateCredentialEngine.valueFor(role)),
                presentation,
            )
        }

        val response = FillResponse.Builder().addDataset(dataset.build()).build()
        setResult(
            RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response),
        )
        finish()
    }

    @Suppress("DEPRECATION")
    private fun parcelableIds(): List<AutofillId> =
        intent.getParcelableArrayListExtra<AutofillId>(EXTRA_AUTOFILL_IDS).orEmpty()

    private fun cancelAndFinish() {
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val EXTRA_TARGET_PACKAGE = "com.upspa.mobile.extra.TARGET_PACKAGE"
        private const val EXTRA_REQUEST_TOKEN = "com.upspa.mobile.extra.REQUEST_TOKEN"
        private const val EXTRA_AUTOFILL_IDS = "com.upspa.mobile.extra.AUTOFILL_IDS"
        private const val EXTRA_ROLES = "com.upspa.mobile.extra.ROLES"

        fun newIntent(
            context: Context,
            targetPackage: String,
            requestToken: String,
            fields: List<FieldClassifier.Field>,
        ): Intent = Intent(context, CredentialAuthActivity::class.java).apply {
            // Fields without an autofill id cannot be addressed; dropping them here keeps the id
            // array and the role array index-aligned.
            val addressable = fields.filter { it.autofillId != null }
            putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(EXTRA_REQUEST_TOKEN, requestToken)
            putParcelableArrayListExtra(
                EXTRA_AUTOFILL_IDS,
                ArrayList(addressable.mapNotNull { it.autofillId }),
            )
            putExtra(EXTRA_ROLES, addressable.map { it.role.ordinal }.toIntArray())
        }
    }
}
