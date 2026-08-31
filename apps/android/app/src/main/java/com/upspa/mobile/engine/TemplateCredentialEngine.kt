package com.upspa.mobile.engine

import com.upspa.mobile.autofill.FieldClassifier

/**
 * Synthetic walking-skeleton engine.
 *
 * This object must be deleted when the reviewed UniFFI command/effect contract is connected.
 * It never accepts a master password and every returned value is intentionally unusable.
 */
object TemplateCredentialEngine {
    const val DISPLAY_NAME = "template-user"

    fun valueFor(role: FieldClassifier.Role): String = when (role) {
        FieldClassifier.Role.USERNAME -> DISPLAY_NAME
        FieldClassifier.Role.EMAIL -> "template@example.invalid"
        FieldClassifier.Role.PASSWORD_CURRENT,
        FieldClassifier.Role.PASSWORD_NEW -> "UPSPA-TEMPLATE-NOT-A-REAL-CREDENTIAL"
        FieldClassifier.Role.OTP -> "000000"
        FieldClassifier.Role.UNKNOWN -> "UPSPA-TEMPLATE-UNCLASSIFIED"
    }
}
