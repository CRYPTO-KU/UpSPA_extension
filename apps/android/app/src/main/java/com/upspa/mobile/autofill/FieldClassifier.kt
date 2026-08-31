package com.upspa.mobile.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.autofill.HintConstants

/**
 * Conservative bootstrap classifier derived from the Android research artifact.
 *
 * It never reads ViewNode text. Only visible, enabled text nodes can be fill targets.
 */
object FieldClassifier {
    enum class Role { USERNAME, EMAIL, PASSWORD_CURRENT, PASSWORD_NEW, OTP, UNKNOWN }

    enum class ScreenIntent { LOGIN, REGISTRATION, PASSWORD_CHANGE, UNKNOWN }

    data class Field(
        val autofillId: AutofillId,
        val hints: List<String>,
        val htmlAutocomplete: String?,
        val passwordInput: Boolean,
        val attributeCorpus: String,
        var role: Role = Role.UNKNOWN,
        var tier: Int = 0,
    )

    data class Result(val fields: List<Field>, val intent: ScreenIntent) {
        val fillable: List<Field> get() = fields.filter { it.role != Role.UNKNOWN }

        fun safeSummary(): String = fillable.joinToString(",") { "${it.role}:T${it.tier}" }
    }

    private val platformHints = mapOf(
        View.AUTOFILL_HINT_USERNAME to Role.USERNAME,
        View.AUTOFILL_HINT_EMAIL_ADDRESS to Role.EMAIL,
        View.AUTOFILL_HINT_PASSWORD to Role.PASSWORD_CURRENT,
        HintConstants.AUTOFILL_HINT_NEW_PASSWORD to Role.PASSWORD_NEW,
        HintConstants.AUTOFILL_HINT_NEW_USERNAME to Role.USERNAME,
        HintConstants.AUTOFILL_HINT_SMS_OTP to Role.OTP,
    )

    private val htmlHints = mapOf(
        "username" to Role.USERNAME,
        "email" to Role.EMAIL,
        "current-password" to Role.PASSWORD_CURRENT,
        "new-password" to Role.PASSWORD_NEW,
        "one-time-code" to Role.OTP,
    )

    private val poison = Regex(
        "search|captcha|coupon|promo|gift|card.?number|cvv|cvc|expir|amount|city|zip|postal|street|address",
        RegexOption.IGNORE_CASE,
    )
    private val email = Regex("e.?mail", RegexOption.IGNORE_CASE)
    private val username = Regex("user|login|account|nick|identifier|member", RegexOption.IGNORE_CASE)
    private val currentPassword = Regex("current|old|existing", RegexOption.IGNORE_CASE)
    private val newPassword = Regex("new|confirm|repeat|again|retype|verify", RegexOption.IGNORE_CASE)

    fun classify(structure: AssistStructure): Result {
        val fields = mutableListOf<Field>()
        for (windowIndex in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(windowIndex).rootViewNode, fields)
        }
        fields.forEach(::classifyField)
        return Result(fields, applyTopology(fields))
    }

    private fun collect(node: AssistStructure.ViewNode, output: MutableList<Field>) {
        val id = node.autofillId
        if (
            id != null &&
            node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            node.visibility == View.VISIBLE &&
            node.isEnabled
        ) {
            output += Field(
                autofillId = id,
                hints = node.autofillHints?.toList().orEmpty(),
                htmlAutocomplete = htmlAttribute(node, "autocomplete"),
                passwordInput = isPasswordInput(node),
                attributeCorpus = attributeCorpus(node),
            )
        }
        for (childIndex in 0 until node.childCount) {
            collect(node.getChildAt(childIndex), output)
        }
    }

    private fun classifyField(field: Field) {
        field.hints.firstNotNullOfOrNull(platformHints::get)?.let { role ->
            field.role = role
            field.tier = 1
            return
        }

        field.htmlAutocomplete
            ?.split(' ')
            ?.firstNotNullOfOrNull { htmlHints[it.trim().lowercase()] }
            ?.let { role ->
                field.role = role
                field.tier = 1
                return
            }

        if (poison.containsMatchIn(field.attributeCorpus)) return

        if (field.passwordInput) {
            field.role = when {
                currentPassword.containsMatchIn(field.attributeCorpus) -> Role.PASSWORD_CURRENT
                newPassword.containsMatchIn(field.attributeCorpus) -> Role.PASSWORD_NEW
                else -> Role.PASSWORD_CURRENT
            }
            field.tier = 2
            return
        }

        field.role = when {
            email.containsMatchIn(field.attributeCorpus) -> Role.EMAIL
            username.containsMatchIn(field.attributeCorpus) -> Role.USERNAME
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
                currentPassword.containsMatchIn(it.attributeCorpus)
        }

        if (passwords.size == 1 && identifiers.isEmpty()) {
            val passwordIndex = fields.indexOf(passwords.single())
            fields.subList(0, passwordIndex)
                .lastOrNull {
                    it.role == Role.UNKNOWN &&
                        !it.passwordInput &&
                        !poison.containsMatchIn(it.attributeCorpus)
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
                        (currentPassword.containsMatchIn(field.attributeCorpus) || index == 0)
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

    private fun isPasswordInput(node: AssistStructure.ViewNode): Boolean {
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val textPassword = inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        val numberPassword = inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return textPassword || numberPassword || htmlAttribute(node, "type") == "password"
    }

    private fun attributeCorpus(node: AssistStructure.ViewNode): String = listOfNotNull(
        node.idEntry,
        node.hint,
        node.contentDescription?.toString(),
        htmlAttribute(node, "name"),
        htmlAttribute(node, "id"),
        htmlAttribute(node, "placeholder"),
        htmlAttribute(node, "label"),
    ).joinToString("|")

    private fun htmlAttribute(node: AssistStructure.ViewNode, name: String): String? =
        node.htmlInfo?.attributes?.firstOrNull { it.first == name }?.second
}
