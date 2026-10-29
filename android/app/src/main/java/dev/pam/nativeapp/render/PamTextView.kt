package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextUtils
import android.view.MotionEvent
import android.widget.TextView
import java.text.BreakIterator

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
     * React Native tail truncation (StaticLayout.calculateEllipsis): the last
     * visible line takes the rest of the text unwrapped and keeps every
     * grapheme whose advance plus the ellipsis fits the content width,
     * spaces included ("hoje à…", "🔥🖤 …"). TextView's own single-line
     * ellipsis (TextUtils.ellipsize) trims the spaces before the ellipsis and
     * a wrapped maxLines layout ellipsizes after the last whole word.
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
        val full = StaticLayout.Builder.obtain(source, 0, source.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .build()
        if (full.lineCount <= lines) {
            restoreSource()
            return
        }
        val lastStart = full.getLineStart(lines - 1)
        val rest = source.subSequence(lastStart, source.length)
        val ellipsis = "\u2026"
        val ellipsisWidth = paint.measureText(ellipsis)
        // One unwrapped line of the remaining text: shaped advances.
        val line = StaticLayout.Builder.obtain(rest, 0, rest.length, paint, Int.MAX_VALUE / 4)
            .setIncludePad(includeFontPadding)
            .build()
        val boundaries = graphemeBoundaries(rest.toString())
        val advances = FloatArray(boundaries.size) { line.getPrimaryHorizontal(boundaries[it]) }
        val keep = tailEllipsisCut(boundaries, advances, contentWidth.toFloat(), ellipsisWidth)
        val truncated = SpannableStringBuilder(source, 0, lastStart + keep).append(ellipsis)
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

private fun graphemeBoundaries(text: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val out = ArrayList<Int>()
    var index = iterator.first()
    while (index != BreakIterator.DONE) {
        out.add(index)
        index = iterator.next()
    }
    return out.toIntArray()
}

/**
 * React Native's tail cut (StaticLayout.calculateEllipsis, END): the longest
 * grapheme prefix whose advance plus the ellipsis fits [available]; trailing
 * spaces are kept. [boundaries] are grapheme offsets (0 first) and
 * [advances] the line x at each of them.
 */
internal fun tailEllipsisCut(boundaries: IntArray, advances: FloatArray, available: Float, ellipsisWidth: Float): Int {
    var keep = 0
    for (i in boundaries.indices) {
        if (advances[i] + ellipsisWidth > available) break
        keep = boundaries[i]
    }
    return keep
}
