package com.upspa.mobile.autofill

import android.app.PendingIntent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.RemoteViews
import com.upspa.mobile.R
import com.upspa.mobile.secureui.CredentialAuthActivity
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UpspaAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val canceled = AtomicBoolean(false)
        cancellationSignal.setOnCancelListener { canceled.set(true) }

        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null || canceled.get()) {
                callback.onSuccess(null)
                return
            }

            val targetPackage = structure.activityComponent?.packageName
            val result = FieldClassifier.classify(structure)
            if (targetPackage.isNullOrBlank() || result.fillable.isEmpty() || canceled.get()) {
                callback.onSuccess(null)
                return
            }

            val requestToken = UUID.randomUUID().toString()
            val authIntent = CredentialAuthActivity.newIntent(
                context = this,
                targetPackage = targetPackage,
                requestToken = requestToken,
                fields = result.fillable,
            )

            var flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // AutofillManager attaches framework extras before launching the Activity.
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val intentSender = PendingIntent.getActivity(
                this,
                requestCounter.incrementAndGet(),
                authIntent,
                flags,
            ).intentSender

            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, getString(R.string.locked_entry))
            }
            val ids = result.fillable.map { it.autofillId }.toTypedArray()
            @Suppress("DEPRECATION")
            val response = FillResponse.Builder()
                .setAuthentication(ids, intentSender, presentation)
                .build()

            if (canceled.get()) callback.onSuccess(null) else callback.onSuccess(response)
            Log.i(TAG, "locked response target=$targetPackage roles=${result.safeSummary()}")
        } catch (error: Throwable) {
            Log.e(TAG, "fill failed type=${error.javaClass.simpleName}")
            callback.onFailure("UpSPA bootstrap could not prepare a locked entry")
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Do not inspect or log submitted field values in the walking skeleton.
        callback.onSuccess()
    }

    companion object {
        private const val TAG = "UpspaAutofill"
        private val requestCounter = AtomicInteger(0)
    }
}
