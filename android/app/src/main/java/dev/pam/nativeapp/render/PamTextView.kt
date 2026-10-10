package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.widget.TextView

/**
 * Text node view. A text without an allocated width whose parent centers
 * (or end-aligns) it is a shrink-wrapped box in React Native: Yoga sizes it
 * to its widest line and places that box, while the lines inside keep the
 * natural (start) alignment. The engine lays such a text out at the full
 * available width, so the block is shifted here instead of centering every
 * line (`Gravity.CENTER_HORIZONTAL`), which wrongly centered each line of a
 * wrapped paragraph.
 */
internal class PamTextView(context: Context) : TextView(context) {
    /** 0 = start (no shift), 0.5 = centered block, 1 = end-aligned block. */
    var blockAlignment: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    internal fun blockOffset(): Float {
        val layout = layout ?: return 0f
        if (blockAlignment <= 0f || layout.lineCount == 0) return 0f
        var widest = 0f
        for (line in 0 until layout.lineCount) widest = maxOf(widest, layout.getLineWidth(line))
        val content = (width - totalPaddingLeft - totalPaddingRight).toFloat()
        return ((content - widest) * blockAlignment).coerceAtLeast(0f)
    }

    override fun onDraw(canvas: Canvas) {
        val offset = blockOffset()
        if (offset == 0f) {
            super.onDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.translate(if (layoutDirection == LAYOUT_DIRECTION_RTL) -offset else offset, 0f)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val offset = blockOffset()
        if (offset == 0f) return super.onTouchEvent(event)
        // Links (ClickableSpan) hit-test in layout coordinates.
        val shifted = MotionEvent.obtain(event)
        shifted.offsetLocation(if (layoutDirection == LAYOUT_DIRECTION_RTL) offset else -offset, 0f)
        return try {
            super.onTouchEvent(shifted)
        } finally {
            shifted.recycle()
        }
    }
}
