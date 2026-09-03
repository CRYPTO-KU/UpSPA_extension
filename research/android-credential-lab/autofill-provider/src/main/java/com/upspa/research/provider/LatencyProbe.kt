package com.upspa.research.provider

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured latency evidence for research topic 11 (credential derivation latency from the
 * Android callback perspective). Log lines use a stable, grep-able format:
 *
 *     UpSpaLatency: BEGIN <probe>
 *     UpSpaLatency: END <probe> elapsedMs=<n>
 *
 * Collect with: adb logcat -s UpSpaLatency
 */
object LatencyProbe {
    private const val TAG = "UpSpaLatency"

    /** System entry: onFillRequest received -> FillResponse delivered. */
    const val PROBE_FILL = "onFillRequest"

    /** User tap on locked entry -> authenticated + (simulated) derivation -> dataset returned. */
    const val PROBE_AUTH_DERIVE = "authAndDerive"

    private val marks = ConcurrentHashMap<String, Long>()

    fun begin(probe: String) {
        marks[probe] = SystemClock.elapsedRealtime()
        Log.i(TAG, "BEGIN $probe")
    }

    fun end(probe: String) {
        val start = marks.remove(probe)
        if (start == null) {
            Log.w(TAG, "END $probe (no matching BEGIN)")
            return
        }
        Log.i(TAG, "END $probe elapsedMs=${SystemClock.elapsedRealtime() - start}")
    }
}
