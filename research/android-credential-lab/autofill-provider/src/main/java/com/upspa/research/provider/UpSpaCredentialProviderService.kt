package com.upspa.research.provider

import android.credentials.ClearCredentialStateException
import android.credentials.CreateCredentialException
import android.credentials.GetCredentialException
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.service.credentials.BeginCreateCredentialRequest
import android.service.credentials.BeginCreateCredentialResponse
import android.service.credentials.BeginGetCredentialRequest
import android.service.credentials.BeginGetCredentialResponse
import android.service.credentials.ClearCredentialStateRequest
import android.service.credentials.CredentialProviderService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Credential Manager provider STUB (API 34+), the comparison arm of research topic 1
 * (AutofillService vs CredentialProviderService).
 *
 * Phase 2 of the research replaces the empty responses with real password entries; this stub
 * exists to prove registration, settings visibility, and binding behavior on API 34+ devices,
 * and to observe callback shapes/latency envelopes for the comparison report.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UpSpaCredentialProviderService : CredentialProviderService() {

    override fun onBeginGetCredential(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        Log.i(TAG, "onBeginGetCredential options=${request.beginGetCredentialOptions.map { it.type }}")
        callback.onResult(BeginGetCredentialResponse.Builder().build())
    }

    override fun onBeginCreateCredential(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        Log.i(TAG, "onBeginCreateCredential type=${request.type}")
        callback.onError(
            CreateCredentialException(
                CreateCredentialException.TYPE_UNKNOWN,
                "Research stub: credential creation is not implemented",
            ),
        )
    }

    override fun onClearCredentialState(
        request: ClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void, ClearCredentialStateException>,
    ) {
        Log.i(TAG, "onClearCredentialState")
        callback.onResult(null)
    }

    companion object {
        private const val TAG = "UpSpaCredProvider"
    }
}
