package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
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

    /** The full text set by the renderer; [getText] may hold its tail-truncated copy. */
    private var sourceText: CharSequence? = null
    private var settingTruncated = false
    private var truncatedFor: List<Any?>? = null

    /** The full text the renderer set (what `onTextLayout` measures), not the tail-truncated copy on screen. */
    internal fun sourceContent(): CharSequence? = sourceText

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!settingTruncated) {
            sourceText = text
            truncatedFor = null
        }
        super.setText(text, type)
        // A new text at an already laid-out width gets no measure pass when
        // its size does not change: truncate it here against that width.
        if (!settingTruncated && width > 0) {
            truncateTail(width - compoundPaddingLeft - compoundPaddingRight)
        }
    }

    /**
     * React Native tail truncation: the text is cut where RN 0.85's
     * TextLayoutManager layout ellipsizes it ([PamTextLayout.rnTailTruncate])
     * and the copy is shown as plain lines that fit. TextView's own
     * single-line ellipsis (TextUtils.ellipsize) trims the space before the
     * ellipsis and ellipsizes at a different width.
     */
    private fun truncateTail(contentWidth: Int) {
        val source = sourceText ?: return
        val lines = maxLines
        val tail = ellipsize == TextUtils.TruncateAt.END
        if (!tail || lines <= 0 || lines == Int.MAX_VALUE || contentWidth <= 0 || source.isEmpty()) {
            restoreSource()
            return
        }
        // The paint is part of the key: font size, family, weight and letter
        // spacing often land after the first measure (batched text props).
        val key = listOf(source, contentWidth, lines, paint.textSize, paint.typeface, paint.letterSpacing, paint.textScaleX, paint.isFakeBoldText)
        if (truncatedFor == key) return
        truncatedFor = key
        // The truncated copy is laid out as wrapped lines that already fit;
        // an unwrapped (horizontally scrolling) line would clip instead.
        if (lines == 1) setHorizontallyScrolling(false)
        val truncated = PamTextLayout.rnTailTruncate(
            source,
            paint,
            contentWidth.toFloat(),
            lines,
            includeFontPadding,
            breakStrategy,
            hyphenationFrequency,
        )
        if (truncated == null) {
            restoreSource()
            return
        }
        settingTruncated = true
        try {
            super.setText(truncated, BufferType.SPANNABLE)
        } finally {
            settingTruncated = false
        }
    }

    private fun restoreSource() {
        val source = sourceText ?: return
        if (text === source || text.toString() == source.toString()) return
        settingTruncated = true
        try {
            super.setText(source, BufferType.NORMAL)
        } finally {
            settingTruncated = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) truncateTail(width)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Paint changes (font, size, spacing) at an unchanged size reach here
        // without onMeasure re-running the truncation for the new metrics.
        truncateTail(right - left - compoundPaddingLeft - compoundPaddingRight)
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A layout pass that skipped measure at this width still truncates
        // against the real content width.
        val before = text
        truncateTail(w - compoundPaddingLeft - compoundPaddingRight)
        if (text !== before) post { requestLayout() }
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
