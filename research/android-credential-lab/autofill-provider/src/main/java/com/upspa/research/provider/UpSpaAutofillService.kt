package com.upspa.research.provider

import android.app.PendingIntent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal Autofill service for the UpSPA research track.
 *
 * Design under test: a single LOCKED static entry is returned for every classified screen.
 * Tapping it launches [AuthActivity] (secure authentication) which returns the actual
 * dataset containing FAKE credentials — mirroring how a real UpSPA provider would defer
 * TOPRF derivation until after user authentication.
 */
class UpSpaAutofillService : AutofillService() {

    override fun onConnected() {
        Log.i(TAG, "onConnected")
    }

    override fun onDisconnected() {
        Log.i(TAG, "onDisconnected")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        LatencyProbe.begin(LatencyProbe.PROBE_FILL)
        // The system cancels fill requests that exceed its 5 s deadline (AOSP
        // RemoteFillService.TIMEOUT_REMOTE_REQUEST_MILLIS, source ledger S-7). Logging the
        // signal turns every timeout into direct evidence for the EXP-004 latency sweep.
        cancellationSignal.setOnCancelListener {
            Log.w(TAG, "onFillRequest cancelled by the system (deadline exceeded or context gone)")
        }
        try {
            // The last fill context describes the currently focused screen state; earlier
            // contexts matter for multi-screen flows (research topic 6).
            val structure = request.fillContexts.last().structure
            val requestingPackage = structure.activityComponent?.packageName ?: "unknown"
            val result = FieldClassifier.classify(structure)
            Log.i(TAG, "fillRequest pkg=$requestingPackage ${result.describe()}")

            val fillable = result.fillable
            if (fillable.isEmpty()) {
                callback.onSuccess(null)
                return
            }

            val targetIds = fillable.map { it.autofillId }.toTypedArray()
            val authIntent = AuthActivity.newIntent(this, requestingPackage, fillable)
            var piFlags = PendingIntent.FLAG_CANCEL_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The platform attaches extras to the authentication intent, so it must be mutable.
                piFlags = piFlags or PendingIntent.FLAG_MUTABLE
            }
            val intentSender = PendingIntent
                .getActivity(this, requestCounter.incrementAndGet(), authIntent, piFlags)
                .intentSender

            val lockedPresentation = RemoteViews(packageName, R.layout.item_locked_entry)
            @Suppress("DEPRECATION") // Presentations API exists only on 33+; this lab floors at 26.
            val responseBuilder = FillResponse.Builder()
                .setAuthentication(targetIds, intentSender, lockedPresentation)
            buildSaveInfo(fillable)?.let(responseBuilder::setSaveInfo)

            callback.onSuccess(responseBuilder.build())
        } catch (t: Throwable) {
            // Contract (source ledger S-1): exactly one onSuccess/onFailure per request,
            // otherwise the request times out and is discarded. Never leak structure or
            // value details into the failure message — exception type only.
            Log.e(TAG, "onFillRequest failed", t)
            callback.onFailure("UpSPA research provider error: ${t.javaClass.simpleName}")
        } finally {
            LatencyProbe.end(LatencyProbe.PROBE_FILL)
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Research build: nothing is persisted and submitted values are intentionally NOT
        // logged. The callback itself is the evidence (save-prompt behavior, topic 3).
        Log.i(TAG, "onSaveRequest received (${request.fillContexts.size} fill contexts) — ignored by design")
        callback.onSuccess()
    }

    private fun buildSaveInfo(fillable: List<FieldClassifier.Field>): SaveInfo? {
        val password = fillable.firstOrNull {
            it.role == FieldClassifier.Role.PASSWORD_CURRENT || it.role == FieldClassifier.Role.PASSWORD_NEW
        } ?: return null
        val identifier = fillable.firstOrNull {
            it.role == FieldClassifier.Role.USERNAME || it.role == FieldClassifier.Role.EMAIL
        }

        var saveType = SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (identifier != null) saveType = saveType or SaveInfo.SAVE_DATA_TYPE_USERNAME

        val builder = SaveInfo.Builder(saveType, arrayOf(password.autofillId))
        identifier?.let { builder.setOptionalIds(arrayOf(it.autofillId)) }
        return builder.build()
    }

    companion object {
        private const val TAG = "UpSpaAutofill"
        private val requestCounter = AtomicInteger(0)
    }
}
