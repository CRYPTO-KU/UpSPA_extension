package com.upspa.research.fixtures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewStructure
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue

/**
 * Custom-drawn login form exposing two VIRTUAL autofill children (research topic 7).
 *
 * This is the pattern real apps with bespoke rendering (games, banking keyboards, canvas UIs)
 * must implement for autofill to work at all:
 *  - [onProvideAutofillVirtualStructure] describes the virtual fields to the framework,
 *  - [autofill] receives the values chosen by the user,
 *  - taps report focus via [AutofillManager.notifyViewEntered]/[AutofillManager.notifyViewExited].
 *
 * Deliberately NO autofillHints on the virtual children: only inputType and hint text are
 * exposed, so the provider's Tier 2/3 classification is what gets exercised.
 */
class CustomLoginView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class VirtualField(
        val virtualId: Int,
        val label: String,
        val masked: Boolean,
        var value: String = "",
    )

    private val fields = listOf(
        VirtualField(VIRTUAL_ID_USERNAME, "Username", masked = false),
        VirtualField(VIRTUAL_ID_PASSWORD, "Password", masked = true),
    )
    private var focusedVirtualId = NO_FIELD

    private val density = resources.displayMetrics.density
    private val labelHeight = 20 * density
    private val boxHeight = 56 * density
    private val fieldSpacing = 16 * density
    private val textPadding = 12 * density

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14 * density
        color = 0xFF616161.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * density
        color = 0xFF212121.toInt()
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFF9E9E9E.toInt()
    }
    private val focusedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFF1565C0.toInt()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    // ---- Autofill framework integration -------------------------------------------------

    override fun onProvideAutofillVirtualStructure(structure: ViewStructure, flags: Int) {
        val parentAutofillId = autofillId ?: return
        val startIndex = structure.addChildCount(fields.size)
        fields.forEachIndexed { index, field ->
            structure.newChild(startIndex + index).apply {
                setAutofillId(parentAutofillId, field.virtualId)
                setAutofillType(AUTOFILL_TYPE_TEXT)
                setInputType(
                    if (field.masked) {
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    } else {
                        InputType.TYPE_CLASS_TEXT
                    },
                )
                setHint(field.label)
                setVisibility(VISIBLE)
                val bounds = localBoundsOf(index)
                setDimens(bounds.left, bounds.top, 0, 0, bounds.width(), bounds.height())
            }
        }
    }

    override fun autofill(values: SparseArray<AutofillValue>) {
        for (i in 0 until values.size()) {
            val field = fields.firstOrNull { it.virtualId == values.keyAt(i) } ?: continue
            val value = values.valueAt(i)
            if (value.isText) field.value = value.textValue.toString()
        }
        invalidate()
    }

    // ---- Interaction ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tappedIndex = fields.indices.firstOrNull {
                localBoundsOf(it).contains(event.x.toInt(), event.y.toInt())
            }
            if (tappedIndex != null) focusVirtualField(fields[tappedIndex], tappedIndex)
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun focusVirtualField(field: VirtualField, index: Int) {
        val autofillManager = context.getSystemService(AutofillManager::class.java) ?: return
        if (focusedVirtualId != NO_FIELD && focusedVirtualId != field.virtualId) {
            autofillManager.notifyViewExited(this, focusedVirtualId)
        }
        focusedVirtualId = field.virtualId
        autofillManager.notifyViewEntered(this, field.virtualId, screenBoundsOf(index))
        invalidate()
    }

    // ---- Geometry ------------------------------------------------------------------------

    private fun fieldTop(index: Int): Float =
        index * (labelHeight + boxHeight + fieldSpacing)

    private fun localBoundsOf(index: Int): Rect {
        val top = fieldTop(index) + labelHeight
        return Rect(0, top.toInt(), width, (top + boxHeight).toInt())
    }

    private fun screenBoundsOf(index: Int): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val local = localBoundsOf(index)
        local.offset(location[0], location[1])
        return local
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight =
            (fields.size * (labelHeight + boxHeight) + (fields.size - 1) * fieldSpacing).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    // ---- Rendering -----------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        fields.forEachIndexed { index, field ->
            val top = fieldTop(index)
            canvas.drawText(field.label, 0f, top + labelPaint.textSize, labelPaint)

            val box = localBoundsOf(index)
            val focused = field.virtualId == focusedVirtualId
            canvas.drawRect(box, if (focused) focusedBoxPaint else boxPaint)

            val display = when {
                field.value.isEmpty() -> ""
                field.masked -> BULLET.repeat(field.value.length)
                else -> field.value
            }
            if (display.isNotEmpty()) {
                canvas.drawText(
                    display,
                    box.left + textPadding,
                    box.exactCenterY() + valuePaint.textSize / 3,
                    valuePaint,
                )
            }
        }
    }

    private companion object {
        const val VIRTUAL_ID_USERNAME = 1
        const val VIRTUAL_ID_PASSWORD = 2
        const val NO_FIELD = -1
        const val BULLET = "\u2022"
    }
}
