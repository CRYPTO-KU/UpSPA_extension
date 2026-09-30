package com.upspa.mobile.autofill

import android.app.assist.AssistStructure
import android.os.Build

/**
 * The only place that touches the framework Autofill tree.
 *
 * Keeping the conversion here means [FieldClassifier] has no dependency on classes that cannot be
 * instantiated in a JVM unit test.
 */
object AssistStructureAdapter {
    fun roots(structure: AssistStructure): List<ViewNodeSnapshot> =
        (0 until structure.windowNodeCount).map { index ->
            snapshot(structure.getWindowNodeAt(index).rootViewNode)
        }

    private fun snapshot(node: AssistStructure.ViewNode): ViewNodeSnapshot = ViewNodeSnapshot(
        autofillId = node.autofillId,
        hasAutofillId = node.autofillId != null,
        debugKey = node.idEntry ?: node.className.orEmpty(),
        autofillType = node.autofillType,
        visibility = node.visibility,
        enabled = node.isEnabled,
        importantForAutofill = importantForAutofill(node),
        hints = node.autofillHints?.filterNotNull().orEmpty(),
        htmlAttributes = htmlAttributes(node),
        inputType = node.inputType,
        idEntry = node.idEntry,
        hint = node.hint,
        contentDescription = node.contentDescription?.toString(),
        children = (0 until node.childCount).map { index -> snapshot(node.getChildAt(index)) },
    )

    /** Unavailable below API 28; a missing mode must not be read as "important". */
    private fun importantForAutofill(node: AssistStructure.ViewNode): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.importantForAutofill else null

    private fun htmlAttributes(node: AssistStructure.ViewNode): Map<String, String> =
        node.htmlInfo?.attributes.orEmpty()
            .mapNotNull { attribute ->
                val name = attribute.first ?: return@mapNotNull null
                val value = attribute.second ?: return@mapNotNull null
                name.lowercase() to value
            }
            .toMap()
}
