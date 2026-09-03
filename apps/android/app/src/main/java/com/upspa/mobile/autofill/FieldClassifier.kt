package com.upspa.mobile.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.annotation.VisibleForTesting
import androidx.autofill.HintConstants

/**
 * Conservative bootstrap classifier derived from the Android research artifact.
 *
 * It never reads node text. A node can only become a fill target when it is a visible, enabled,
 * autofill-important text node that the platform gave an autofill id, and when its attribute
 * corpus does not match a poison term.
 *
 * Confidence tiers, highest first:
 *  - tier 1: an explicit platform autofill hint or an HTML `autocomplete` token;
 *  - tier 2: the poison-filtered attribute corpus plus the input type;
 *  - tier 3: screen topology, used only to repair an otherwise incomplete form.
 */
class FieldClassifier @VisibleForTesting internal constructor(private val policy: Policy) {

    enum class Role { USERNAME, EMAIL, PASSWORD_CURRENT, PASSWORD_NEW, OTP, UNKNOWN }

    enum class ScreenIntent { LOGIN, REGISTRATION, PASSWORD_CHANGE, UNKNOWN }

    data class Field(
        /** Null only in unit tests; see [ViewNodeSnapshot.autofillId]. */
        val autofillId: AutofillId?,
        val debugKey: String,
        val hints: List<String>,
        val htmlAutocomplete: String?,
        val passwordInput: Boolean,
        val attributeCorpus: String,
        var role: Role = Role.UNKNOWN,
        var tier: Int = 0,
    )

    data class Result(val fields: List<Field>, val intent: ScreenIntent) {
        val fillable: List<Field> get() = fields.filter { it.role != Role.UNKNOWN }

        /** Roles and tiers only. Must never expose an attribute corpus or a field value. */
        fun safeSummary(): String = fillable.joinToString(",") { "${it.role}:T${it.tier}" }
    }

    /**
     * The guards that decide whether a node may be filled at all.
     *
     * Every value defaults to the safe setting and production code always uses [Policy.DEFAULT].
     * A weakened policy can only be built inside this module, and exists so that unit tests can
     * disable exactly one guard and prove that the guard is load-bearing. See
     * `FieldClassifierNegativeControlTest`.
     */
    data class Policy(
        val poison: Regex = DEFAULT_POISON,
        val enforceVisibilityGate: Boolean = true,
        val enforceEnabledGate: Boolean = true,
        val enforceImportantForAutofill: Boolean = true,
    ) {
        companion object {
            val DEFAULT_POISON = Regex(
                "search|captcha|coupon|promo|gift|card.?number|cvv|cvc|expir|amount|city|" +
                    "zip|postal|street|address",
                RegexOption.IGNORE_CASE,
            )

            val DEFAULT = Policy()
        }
    }

    fun classify(roots: List<ViewNodeSnapshot>): Result {
        val fields = mutableListOf<Field>()
        roots.forEach { collect(it, fields) }
        fields.forEach(::classifyField)
        return Result(fields, applyTopology(fields))
    }

    private fun collect(node: ViewNodeSnapshot, output: MutableList<Field>) {
        if (isCandidate(node)) {
            output += Field(
                autofillId = node.autofillId,
                debugKey = node.debugKey,
                hints = node.hints,
                htmlAutocomplete = node.htmlAttributes["autocomplete"],
                passwordInput = isPasswordInput(node),
                attributeCorpus = attributeCorpus(node),
            )
        }
        if (excludesDescendants(node)) return
        node.children.forEach { collect(it, output) }
    }

    private fun isCandidate(node: ViewNodeSnapshot): Boolean {
        if (!node.hasAutofillId) return false
        if (node.autofillType != View.AUTOFILL_TYPE_TEXT) return false
        if (policy.enforceVisibilityGate && node.visibility != View.VISIBLE) return false
        if (policy.enforceEnabledGate && !node.enabled) return false
        val importance = node.importantForAutofill
        if (policy.enforceImportantForAutofill && importance != null &&
            importance in UNIMPORTANT_MODES
        ) {
            return false
        }
        return true
    }

    private fun excludesDescendants(node: ViewNodeSnapshot): Boolean {
        if (!policy.enforceImportantForAutofill) return false
        val importance = node.importantForAutofill ?: return false
        return importance in DESCENDANT_EXCLUDING_MODES
    }

    private fun classifyField(field: Field) {
        field.hints.firstNotNullOfOrNull { PLATFORM_HINTS[it] }?.let { role ->
            field.role = role
            field.tier = 1
            return
        }

        field.htmlAutocomplete
            ?.split(' ')
            ?.firstNotNullOfOrNull { HTML_HINTS[it.trim().lowercase()] }
            ?.let { role ->
                field.role = role
                field.tier = 1
                return
            }

        // The poison veto applies below tier 1 only: an explicit hint is a stronger statement of
        // intent than a name that happens to contain a poison term.
        if (policy.poison.containsMatchIn(field.attributeCorpus)) return

        if (field.passwordInput) {
            field.role = when {
                CURRENT_PASSWORD.containsMatchIn(field.attributeCorpus) -> Role.PASSWORD_CURRENT
                NEW_PASSWORD.containsMatchIn(field.attributeCorpus) -> Role.PASSWORD_NEW
                else -> Role.PASSWORD_CURRENT
            }
            field.tier = 2
            return
        }

        field.role = when {
            EMAIL.containsMatchIn(field.attributeCorpus) -> Role.EMAIL
            USERNAME.containsMatchIn(field.attributeCorpus) -> Role.USERNAME
            else -> Role.UNKNOWN
        }
        if (field.role != Role.UNKNOWN) field.tier = 2
    }

    private fun applyTopology(fields: List<Field>): ScreenIntent {
        val passwords = fields.filter {
            it.role == Role.PASSWORD_CURRENT || it.role == Role.PASSWORD_NEW
        }
        val identifiers = fields.filter { it.role == Role.USERNAME || it.role == Role.EMAIL }
        val currentMarker = passwords.any {
            (it.tier == 1 && it.role == Role.PASSWORD_CURRENT) ||
                CURRENT_PASSWORD.containsMatchIn(it.attributeCorpus)
        }

        if (passwords.size == 1 && identifiers.isEmpty()) {
            val passwordIndex = fields.indexOf(passwords.single())
            fields.subList(0, passwordIndex)
                .lastOrNull {
                    it.role == Role.UNKNOWN &&
                        !it.passwordInput &&
                        !policy.poison.containsMatchIn(it.attributeCorpus)
                }
                ?.assign(Role.USERNAME, 3)
        }

        return when {
            passwords.isEmpty() && identifiers.isEmpty() -> ScreenIntent.UNKNOWN
            passwords.isEmpty() -> ScreenIntent.LOGIN
            passwords.size == 1 -> {
                if (passwords.single().role == Role.PASSWORD_NEW) {
                    ScreenIntent.REGISTRATION
                } else {
                    ScreenIntent.LOGIN
                }
            }
            passwords.size == 2 && !currentMarker -> {
                passwords.forEach { if (it.tier != 1) it.assign(Role.PASSWORD_NEW, 3) }
                ScreenIntent.REGISTRATION
            }
            else -> {
                var currentAssigned = passwords.any {
                    it.tier == 1 && it.role == Role.PASSWORD_CURRENT
                }
                passwords.forEachIndexed { index, field ->
                    if (field.tier == 1) return@forEachIndexed
                    if (!currentAssigned &&
                        (CURRENT_PASSWORD.containsMatchIn(field.attributeCorpus) || index == 0)
                    ) {
                        field.assign(Role.PASSWORD_CURRENT, 3)
                        currentAssigned = true
                    } else {
                        field.assign(Role.PASSWORD_NEW, 3)
                    }
                }
                ScreenIntent.PASSWORD_CHANGE
            }
        }
    }

    private fun Field.assign(newRole: Role, newTier: Int) {
        role = newRole
        tier = newTier
    }

    private fun isPasswordInput(node: ViewNodeSnapshot): Boolean {
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val textPassword = inputClass == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS
        val numberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword || node.htmlAttributes["type"] == "password"
    }

    private fun attributeCorpus(node: ViewNodeSnapshot): String = listOfNotNull(
        node.idEntry,
        node.hint,
        node.contentDescription,
        node.htmlAttributes["name"],
        node.htmlAttributes["id"],
        node.htmlAttributes["placeholder"],
        node.htmlAttributes["label"],
    ).joinToString("|")

    companion object {
        private val PLATFORM_HINTS = mapOf(
            View.AUTOFILL_HINT_USERNAME to Role.USERNAME,
            View.AUTOFILL_HINT_EMAIL_ADDRESS to Role.EMAIL,
            View.AUTOFILL_HINT_PASSWORD to Role.PASSWORD_CURRENT,
            HintConstants.AUTOFILL_HINT_NEW_PASSWORD to Role.PASSWORD_NEW,
            HintConstants.AUTOFILL_HINT_NEW_USERNAME to Role.USERNAME,
            HintConstants.AUTOFILL_HINT_SMS_OTP to Role.OTP,
        )

        private val HTML_HINTS = mapOf(
            "username" to Role.USERNAME,
            "email" to Role.EMAIL,
            "current-password" to Role.PASSWORD_CURRENT,
            "new-password" to Role.PASSWORD_NEW,
            "one-time-code" to Role.OTP,
        )

        private val EMAIL = Regex("e.?mail", RegexOption.IGNORE_CASE)
        private val USERNAME = Regex("user|login|account|nick|identifier|member", RegexOption.IGNORE_CASE)
        private val CURRENT_PASSWORD = Regex("current|old|existing", RegexOption.IGNORE_CASE)
        private val NEW_PASSWORD = Regex("new|confirm|repeat|again|retype|verify", RegexOption.IGNORE_CASE)

        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )

        /** Modes that make a node itself ineligible. */
        private val UNIMPORTANT_MODES = setOf(
            View.IMPORTANT_FOR_AUTOFILL_NO,
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
        )

        /** Modes that stop the walk from descending into children. */
        private val DESCENDANT_EXCLUDING_MODES = setOf(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS,
        )

        private val default = FieldClassifier(Policy.DEFAULT)

        fun classify(structure: AssistStructure): Result =
            default.classify(AssistStructureAdapter.roots(structure))
    }
}
