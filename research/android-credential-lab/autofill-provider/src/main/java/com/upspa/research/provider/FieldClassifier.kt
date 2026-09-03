package com.upspa.research.provider

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.autofill.HintConstants

/**
 * Three-tier field classification, ported as a HYPOTHESIS from the browser-extension
 * architecture (docs/emirhan-universal-autofill-architecture.md) to be validated against
 * Android evidence:
 *
 *  - Tier 1: authoritative signals — `android:autofillHints` and, for WebView-backed nodes,
 *    the HTML `autocomplete` attribute surfaced via [AssistStructure.ViewNode.getHtmlInfo].
 *  - Tier 2: weighted attribute heuristics over resource id / hint / content description,
 *    with poison terms (search, captcha, payment, ...) that veto classification.
 *  - Tier 3: password-field topology deriving screen intent (login / registration /
 *    password change) independently of language.
 *
 * Secret hygiene: the classifier never reads [AssistStructure.ViewNode.getText] — user-typed
 * values must not enter the corpus, logs, or any derived artifact.
 */
object FieldClassifier {

    enum class Role { USERNAME, EMAIL, PASSWORD_CURRENT, PASSWORD_NEW, OTP, UNKNOWN }

    enum class ScreenIntent { LOGIN, REGISTRATION, PASSWORD_CHANGE, UNKNOWN }

    data class Field(
        val autofillId: AutofillId,
        val hints: List<String>,
        val htmlAutocomplete: String?,
        val isPasswordInput: Boolean,
        val corpus: String,
        var role: Role = Role.UNKNOWN,
        var tier: Int = 0,
        var confidence: Float = 0f,
    )

    data class Result(val fields: List<Field>, val intent: ScreenIntent) {
        val fillable: List<Field> get() = fields.filter { it.role != Role.UNKNOWN }

        /** Logcat-safe summary: roles and tiers only, never field contents. */
        fun describe(): String =
            "intent=$intent fields=[" + fields.joinToString(", ") {
                "${it.role}(tier=${it.tier},conf=${it.confidence})"
            } + "]"
    }

    private val tier1HintMap = mapOf(
        View.AUTOFILL_HINT_USERNAME to Role.USERNAME,
        View.AUTOFILL_HINT_EMAIL_ADDRESS to Role.EMAIL,
        View.AUTOFILL_HINT_PASSWORD to Role.PASSWORD_CURRENT,
        HintConstants.AUTOFILL_HINT_NEW_PASSWORD to Role.PASSWORD_NEW,
        HintConstants.AUTOFILL_HINT_NEW_USERNAME to Role.USERNAME,
        HintConstants.AUTOFILL_HINT_SMS_OTP to Role.OTP,
    )

    private val tier1AutocompleteMap = mapOf(
        "username" to Role.USERNAME,
        "email" to Role.EMAIL,
        "current-password" to Role.PASSWORD_CURRENT,
        "new-password" to Role.PASSWORD_NEW,
        "one-time-code" to Role.OTP,
    )

    // Tier 2 heuristics. Poison terms veto classification outright: grabbing a search box is
    // worse than missing a login field (same trade-off as the browser engine).
    private val poisonRe = Regex(
        "search|captcha|coupon|promo|gift|card.?number|cvv|cvc|expir|amount|city|zip|postal|street|address",
        RegexOption.IGNORE_CASE,
    )
    private val otpRe = Regex("\\botp\\b|one.?time|verification.?code|\\b2fa\\b|totp", RegexOption.IGNORE_CASE)
    private val emailRe = Regex("e.?mail", RegexOption.IGNORE_CASE)
    private val userRe = Regex("user|login|account|nick|identifier|member", RegexOption.IGNORE_CASE)
    private val currentPwdRe = Regex("current|old|existing", RegexOption.IGNORE_CASE)
    private val newPwdRe = Regex("new|confirm|repeat|again|retype|verify", RegexOption.IGNORE_CASE)

    fun classify(structure: AssistStructure): Result {
        val fields = mutableListOf<Field>()
        for (i in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(i).rootViewNode, fields)
        }
        fields.forEach(::classifyField)
        val intent = applyTopology(fields)
        return Result(fields, intent)
    }

    private fun collect(node: AssistStructure.ViewNode, out: MutableList<Field>) {
        val autofillId = node.autofillId
        // Candidate gating (security audit): only visible AND enabled text nodes may become
        // fill targets. View.INVISIBLE / View.GONE nodes are rejected — filling fields the
        // user cannot see is the "hidden fields" credential-exfiltration primitive from
        // Aonzo et al., CCS 2018 (source ledger A-1). Disabled fields are rejected because
        // they cannot legitimately receive user input, so filling them is a phantom fill.
        if (autofillId != null &&
            node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            node.visibility == View.VISIBLE &&
            node.isEnabled
        ) {
            out.add(
                Field(
                    autofillId = autofillId,
                    hints = node.autofillHints?.toList().orEmpty(),
                    htmlAutocomplete = htmlAttr(node, "autocomplete"),
                    isPasswordInput = isPasswordInput(node),
                    corpus = corpusOf(node),
                ),
            )
        }
        for (i in 0 until node.childCount) collect(node.getChildAt(i), out)
    }

    private fun classifyField(field: Field) {
        // Tier 1a: platform autofill hints.
        for (hint in field.hints) {
            tier1HintMap[hint]?.let { role ->
                field.role = role
                field.tier = 1
                field.confidence = 1f
                return
            }
        }
        // Tier 1b: HTML autocomplete tokens (WebView and compat-mode browsers).
        field.htmlAutocomplete?.split(' ')?.forEach { token ->
            tier1AutocompleteMap[token.trim().lowercase()]?.let { role ->
                field.role = role
                field.tier = 1
                field.confidence = 1f
                return
            }
        }

        // Tier 2: heuristics with poison veto.
        if (poisonRe.containsMatchIn(field.corpus)) return

        if (field.isPasswordInput) {
            field.role = when {
                currentPwdRe.containsMatchIn(field.corpus) -> Role.PASSWORD_CURRENT
                newPwdRe.containsMatchIn(field.corpus) -> Role.PASSWORD_NEW
                else -> Role.PASSWORD_CURRENT // provisional; Tier 3 may reassign
            }
            field.tier = 2
            field.confidence = 0.6f
            return
        }

        when {
            otpRe.containsMatchIn(field.corpus) -> field.assign(Role.OTP)
            emailRe.containsMatchIn(field.corpus) -> field.assign(Role.EMAIL)
            userRe.containsMatchIn(field.corpus) -> field.assign(Role.USERNAME)
        }
    }

    private fun Field.assign(newRole: Role) {
        role = newRole
        tier = 2
        confidence = 0.6f
    }

    /**
     * Tier 3: derive screen intent from password-field topology (language independent):
     *  - 1 password (+ optional identifier)           -> LOGIN
     *  - 2 passwords, no "current" marker             -> REGISTRATION (both are new)
     *  - current + new (2 with marker, or 3 fields)   -> PASSWORD_CHANGE
     *  - identifier only                              -> LOGIN (multi-screen first step)
     * Tier 1 (authoritative) roles are never overridden.
     */
    private fun applyTopology(fields: List<Field>): ScreenIntent {
        val passwords = fields.filter { it.role == Role.PASSWORD_CURRENT || it.role == Role.PASSWORD_NEW }
        val identifiers = fields.filter { it.role == Role.USERNAME || it.role == Role.EMAIL }
        val hasCurrentMarker = passwords.any {
            (it.tier == 1 && it.role == Role.PASSWORD_CURRENT) || currentPwdRe.containsMatchIn(it.corpus)
        }

        // Positional fallback: a lone password whose identifier carries no recognizable
        // attributes usually sits right below its username field. Promote the nearest
        // preceding unclassified, non-poisoned text field (structure over text — the same
        // bet as the browser engine's topology tier).
        if (passwords.size == 1 && identifiers.isEmpty()) {
            val passwordIndex = fields.indexOf(passwords[0])
            fields.subList(0, passwordIndex)
                .lastOrNull {
                    it.role == Role.UNKNOWN &&
                        !it.isPasswordInput &&
                        !poisonRe.containsMatchIn(it.corpus)
                }
                ?.promote(Role.USERNAME)
        }

        return when {
            passwords.isEmpty() && identifiers.isEmpty() -> ScreenIntent.UNKNOWN
            passwords.isEmpty() -> ScreenIntent.LOGIN
            passwords.size == 1 ->
                if (passwords[0].role == Role.PASSWORD_NEW) ScreenIntent.REGISTRATION else ScreenIntent.LOGIN
            passwords.size == 2 && !hasCurrentMarker -> {
                passwords.forEach { it.promote(Role.PASSWORD_NEW) }
                ScreenIntent.REGISTRATION
            }
            else -> {
                var currentAssigned = passwords.any { it.tier == 1 && it.role == Role.PASSWORD_CURRENT }
                passwords.forEach { field ->
                    if (field.tier == 1) return@forEach
                    if (!currentAssigned &&
                        (currentPwdRe.containsMatchIn(field.corpus) || field === passwords.first())
                    ) {
                        field.promote(Role.PASSWORD_CURRENT)
                        currentAssigned = true
                    } else {
                        field.promote(Role.PASSWORD_NEW)
                    }
                }
                ScreenIntent.PASSWORD_CHANGE
            }
        }
    }

    private fun Field.promote(newRole: Role) {
        if (tier == 1) return
        role = newRole
        tier = 3
        confidence = 0.8f
    }

    private fun isPasswordInput(node: AssistStructure.ViewNode): Boolean {
        val inputType = node.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textPassword = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        val numberPassword =
            cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (textPassword || numberPassword) return true
        return htmlAttr(node, "type") == "password"
    }

    /** Attribute corpus for Tier 2. Never includes the node's text (secret hygiene). */
    private fun corpusOf(node: AssistStructure.ViewNode): String =
        listOfNotNull(
            node.idEntry,
            node.hint,
            node.contentDescription?.toString(),
            htmlAttr(node, "name"),
            htmlAttr(node, "id"),
            htmlAttr(node, "placeholder"),
            htmlAttr(node, "label"),
        ).joinToString("|")

    private fun htmlAttr(node: AssistStructure.ViewNode, attribute: String): String? =
        node.htmlInfo?.attributes?.firstOrNull { it.first == attribute }?.second
}
