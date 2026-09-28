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
            if (structure == null) {
                refuse(callback, "no-structure", null)
                return
            }
            if (canceled.get()) {
                refuse(callback, "canceled", null)
                return
            }

            val targetPackage = structure.activityComponent?.packageName
            if (targetPackage.isNullOrBlank()) {
                refuse(callback, "unknown-package", targetPackage)
                return
            }

            val result = FieldClassifier.classify(structure)
            val decision = LockedResponsePolicy.DEFAULT.decide(result, targetPackage)
            if (decision !is LockedResponsePolicy.Decision.LockedEntry) {
                refuse(callback, "nothing-classified", targetPackage)
                return
            }
            if (canceled.get()) {
                refuse(callback, "canceled", targetPackage)
                return
            }

            // A node without an autofill id cannot be addressed. The classifier already drops
            // those, so this only keeps the ids and the roles aligned if that ever changes.
            val fields = decision.fields.filter { it.autofillId != null }
            if (fields.isEmpty()) {
                refuse(callback, "no-addressable-id", targetPackage)
                return
            }

            val requestToken = UUID.randomUUID().toString()
            val authIntent = CredentialAuthActivity.newIntent(
                context = this,
                targetPackage = targetPackage,
                requestToken = requestToken,
                fields = fields,
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
            val ids = fields.mapNotNull { it.autofillId }.toTypedArray()
            @Suppress("DEPRECATION")
            val response = FillResponse.Builder()
                .setAuthentication(ids, intentSender, presentation)
                .build()

            if (canceled.get()) {
                refuse(callback, "canceled", targetPackage)
                return
            }
            callback.onSuccess(response)
            Log.i(TAG, "locked response target=$targetPackage roles=${result.safeSummary()}")
        } catch (error: Throwable) {
            Log.e(TAG, "fill failed type=${error.javaClass.simpleName}")
            callback.onFailure("UpSPA bootstrap could not prepare a locked entry")
        }
    }

    /**
     * Answer the platform with no response, and say why.
     *
     * A refusal is a security-relevant outcome, so it is logged as explicitly as an offer. The
     * reason is a fixed token; nothing derived from the requesting screen's content is recorded.
     */
    private fun refuse(callback: FillCallback, reason: String, targetPackage: String?) {
        callback.onSuccess(null)
        Log.i(TAG, "no response target=${targetPackage.orEmpty()} reason=$reason")
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
