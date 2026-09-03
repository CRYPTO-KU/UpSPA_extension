package com.upspa.research.fixtures

import android.view.View
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Base class for all fixture screens.
 *
 * Fixtures ship WITHOUT autofillHints by default so heuristic (Tier 2/3) classification is
 * actually exercised. When launched with [EXTRA_SPEC_HINTS] (the launcher switch), hints are
 * applied programmatically, turning the same screen into its spec-compliant Tier 1 variant.
 */
abstract class FixtureActivity : AppCompatActivity() {

    protected val specHintsRequested: Boolean
        get() = intent.getBooleanExtra(EXTRA_SPEC_HINTS, false)

    protected fun applySpecHints(vararg hintedViews: Pair<Int, Array<String>>) {
        if (!specHintsRequested) return
        for ((viewId, hints) in hintedViews) {
            findViewById<View>(viewId).setAutofillHints(*hints)
        }
    }

    /**
     * Fixture "submit": signals form completion to the framework so save-prompt behavior
     * (research topic 3) can be observed without any real submission.
     */
    protected fun signalCommit() {
        getSystemService(AutofillManager::class.java)?.commit()
        Toast.makeText(this, R.string.fixture_submit_note, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SPEC_HINTS = "spec_hints"
    }
}
