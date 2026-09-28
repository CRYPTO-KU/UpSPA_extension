package com.upspa.mobile.autofill

import androidx.annotation.VisibleForTesting
import com.upspa.mobile.engine.TemplateCredentialEngine

/**
 * Decides what a fill request may return before the user has unlocked UpSPA.
 *
 * Security assumption: nothing produced here may contain a credential value. The service turns a
 * [Decision.LockedEntry] into an authentication-only `FillResponse`, so the platform shows a single
 * generic entry and no value is computed for, or handed to, the requesting application until
 * `CredentialAuthActivity` has run inside UpSPA's own process with `FLAG_SECURE` set.
 *
 * Keeping the decision in a pure object is what makes that property testable: a locked decision has
 * no field that could carry a value.
 */
class LockedResponsePolicy @VisibleForTesting internal constructor(
    private val requireAuthentication: Boolean,
) {
    sealed interface Decision {
        /** Nothing safe to offer. The service answers the platform with a null response. */
        data object None : Decision

        /**
         * One generic entry guarded by authentication. Carries the classified fields so the
         * service can address them, and deliberately has no place to put a value.
         */
        data class LockedEntry(val fields: List<FieldClassifier.Field>) : Decision

        /**
         * Only reachable when [requireAuthentication] is disabled, which production code never
         * does. It exists so a negative control can show that removing the lock really does put
         * credential values into the pre-authentication response.
         */
        data class UnlockedEntry(
            val fields: List<FieldClassifier.Field>,
            val values: List<String>,
        ) : Decision
    }

    fun decide(result: FieldClassifier.Result, targetPackage: String?): Decision {
        if (targetPackage.isNullOrBlank()) return Decision.None
        val fields = result.fillable
        if (fields.isEmpty()) return Decision.None

        return if (requireAuthentication) {
            Decision.LockedEntry(fields)
        } else {
            Decision.UnlockedEntry(fields, fields.map { TemplateCredentialEngine.valueFor(it.role) })
        }
    }

    companion object {
        val DEFAULT = LockedResponsePolicy(requireAuthentication = true)
    }
}
