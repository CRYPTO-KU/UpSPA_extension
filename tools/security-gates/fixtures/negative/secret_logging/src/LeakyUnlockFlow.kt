package com.upspa.mobile.fixture.negative

import android.content.SharedPreferences
import android.util.Log

class LeakyUnlockFlow(private val prefs: SharedPreferences) {

    fun onUnlocked(masterPassword: String, ssk: ByteArray) {
        Log.d(TAG, "recovered signing key bytes: ${ssk.contentToString()}")

        prefs.edit()
            .putString("cached_master_password", masterPassword)
            .apply()
    }

    companion object {
        private const val TAG = "LeakyUnlockFlow"
    }
}
