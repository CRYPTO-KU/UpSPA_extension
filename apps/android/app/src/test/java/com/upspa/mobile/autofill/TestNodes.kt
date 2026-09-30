package com.upspa.mobile.autofill

import android.text.InputType
import android.view.View

/**
 * Builders for [ViewNodeSnapshot] trees that mirror the screens in the `:fixtures` module.
 *
 * Every node leaves [ViewNodeSnapshot.autofillId] null, because `AutofillId` cannot be constructed
 * off-device, and sets `hasAutofillId` to say what the platform would have reported. Assertions
 * therefore address fields by [ViewNodeSnapshot.debugKey], which is also what the classifier copies
 * into `Field.debugKey`.
 *
 * All values here are synthetic labels. Nothing in this file is, or resembles, a credential.
 */
internal object TestNodes {
    const val TEXT_INPUT = InputType.TYPE_CLASS_TEXT
    const val PASSWORD_INPUT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    const val NUMBER_INPUT = InputType.TYPE_CLASS_NUMBER
    const val NUMBER_PASSWORD_INPUT = InputType.TYPE_CLASS_NUMBER or
        InputType.TYPE_NUMBER_VARIATION_PASSWORD

    fun field(
        id: String,
        hints: List<String> = emptyList(),
        label: String? = null,
        contentDescription: String? = null,
        inputType: Int = TEXT_INPUT,
        html: Map<String, String> = emptyMap(),
        visibility: Int = View.VISIBLE,
        enabled: Boolean = true,
        importantForAutofill: Int? = null,
        hasAutofillId: Boolean = true,
        autofillType: Int = View.AUTOFILL_TYPE_TEXT,
    ): ViewNodeSnapshot = ViewNodeSnapshot(
        hasAutofillId = hasAutofillId,
        debugKey = id,
        autofillType = autofillType,
        visibility = visibility,
        enabled = enabled,
        importantForAutofill = importantForAutofill,
        hints = hints,
        htmlAttributes = html,
        inputType = inputType,
        idEntry = id,
        hint = label,
        contentDescription = contentDescription,
    )

    fun password(
        id: String,
        hints: List<String> = emptyList(),
        label: String? = null,
        visibility: Int = View.VISIBLE,
        enabled: Boolean = true,
        importantForAutofill: Int? = null,
        inputType: Int = PASSWORD_INPUT,
    ): ViewNodeSnapshot = field(
        id = id,
        hints = hints,
        label = label,
        inputType = inputType,
        visibility = visibility,
        enabled = enabled,
        importantForAutofill = importantForAutofill,
    )

    /** A layout node: it holds children but is never a fill target itself. */
    fun container(
        id: String = "container",
        importantForAutofill: Int? = null,
        children: List<ViewNodeSnapshot>,
    ): ViewNodeSnapshot = ViewNodeSnapshot(
        hasAutofillId = false,
        debugKey = id,
        autofillType = View.AUTOFILL_TYPE_NONE,
        importantForAutofill = importantForAutofill,
        idEntry = id,
        children = children,
    )
}

/** Convenience: classify a flat screen the way a single-window request would arrive. */
internal fun FieldClassifier.classifyScreen(vararg nodes: ViewNodeSnapshot): FieldClassifier.Result =
    classify(listOf(TestNodes.container(children = nodes.toList())))

internal fun FieldClassifier.Result.rolesByKey(): Map<String, FieldClassifier.Role> =
    fields.associate { it.debugKey to it.role }

internal fun FieldClassifier.Result.fillableKeys(): List<String> = fillable.map { it.debugKey }

internal fun FieldClassifier.Result.tierOf(key: String): Int =
    fields.first { it.debugKey == key }.tier
