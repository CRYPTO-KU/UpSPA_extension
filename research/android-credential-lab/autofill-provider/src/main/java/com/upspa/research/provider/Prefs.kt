package com.upspa.research.provider

/** Non-secret research knobs shared between MainActivity and AuthActivity. */
object Prefs {
    const val FILE = "research_prefs"

    /** Artificial delay (ms) applied after authentication, modeling TOPRF round-trips. */
    const val KEY_LATENCY_MS = "simulated_derivation_latency_ms"
}
