package com.upspa.mobile.autofill

import android.view.View
import android.view.autofill.AutofillId

/**
 * Platform-independent view of a single node in an Autofill request.
 *
 * [FieldClassifier] reads only snapshots. `AssistStructure.ViewNode` has no public constructor and
 * cannot be assembled by a test, so the framework tree is converted by [AssistStructureAdapter]
 * before it reaches any decision logic. That keeps the security-relevant rules (poison veto,
 * visibility and enabled gates, importance handling) reachable from JVM unit tests.
 *
 * A snapshot never carries the text a user typed. Only structural metadata and developer-authored
 * attributes are copied.
 */
data class ViewNodeSnapshot(
    /**
     * Null in unit tests, because [AutofillId] cannot be constructed off-device. Production nodes
     * always carry one; use [hasAutofillId] to express whether the platform supplied it.
     */
    val autofillId: AutofillId? = null,
    /**
     * Whether the platform reported an autofill id for this node. Tests set this to `true` while
     * leaving [autofillId] null so that the collection gate behaves as it does on a device.
     */
    val hasAutofillId: Boolean = autofillId != null,
    /**
     * Stable label used for assertions and redacted logging. Derived from the view id resource
     * name or the widget class name, never from field content.
     */
    val debugKey: String = "",
    val autofillType: Int = View.AUTOFILL_TYPE_TEXT,
    val visibility: Int = View.VISIBLE,
    val enabled: Boolean = true,
    /**
     * `AssistStructure.ViewNode.getImportantForAutofill()` was added in API 28. Null means the
     * platform did not report a mode, in which case the node is treated as
     * [View.IMPORTANT_FOR_AUTOFILL_AUTO] rather than as important.
     */
    val importantForAutofill: Int? = null,
    val hints: List<String> = emptyList(),
    /** Lower-cased HTML attribute names to values, populated for WebView nodes only. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val contentDescription: String? = null,
    val children: List<ViewNodeSnapshot> = emptyList(),
)
