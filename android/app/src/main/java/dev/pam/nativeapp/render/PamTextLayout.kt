package dev.pam.nativeapp.render

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.BoringLayout
import android.text.Layout
import android.text.NoCopySpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.content.res.Resources
import android.util.TypedValue
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One inline run of a rich `Text` node, decoded from the `TextSpans` wire value.
 *
 * Wire form: spans separated by `;`, fields by `,` in this order:
 * `start,end,fontSize,fontWeight,fontStyle,color,backgroundColor,decoration,
 * letterSpacing,fontFamily,press,textTransform`. Offsets count Unicode code
 * points of the node's `Text`; empty fields inherit the node style.
 */
internal data class PamTextSpanSpec(
    val start: Int,
    val end: Int,
    val fontSize: Float? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val color: Int? = null,
    val backgroundColor: Int? = null,
    val decoration: Int? = null,
    val letterSpacing: Float? = null,
    val fontFamily: String? = null,
    val press: Int? = null,
    val textTransform: Int? = null,
) {
    companion object {
        fun parse(wire: String?): List<PamTextSpanSpec> {
            if (wire.isNullOrEmpty()) return emptyList()
            return wire.split(';').mapNotNull { record ->
                if (record.isEmpty()) return@mapNotNull null
                val fields = record.split(',')
                fun field(index: Int): String? = fields.getOrNull(index)?.takeIf(String::isNotEmpty)
                val start = field(0)?.toIntOrNull() ?: return@mapNotNull null
                val end = field(1)?.toIntOrNull() ?: return@mapNotNull null
                if (start < 0 || end <= start) return@mapNotNull null
                PamTextSpanSpec(
                    start = start,
                    end = end,
                    fontSize = field(2)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f },
                    fontWeight = field(3)?.toIntOrNull()?.coerceIn(1, 1000),
                    italic = field(4)?.toIntOrNull()?.let { it == 2 },
                    color = field(5)?.toLongOrNull()?.toInt(),
                    backgroundColor = field(6)?.toLongOrNull()?.toInt(),
                    decoration = field(7)?.toIntOrNull(),
                    letterSpacing = field(8)?.toFloatOrNull()?.takeIf(Float::isFinite),
                    fontFamily = field(9),
                    press = field(10)?.toIntOrNull()?.takeIf { it >= 0 },
                    textTransform = field(11)?.toIntOrNull(),
                )
            }
        }
    }
}

/** Base text style shared by drawing and measurement. */
internal data class PamTextStyle(
    val fontFamily: String?,
    val fontSize: Float,
    val fontScale: Float,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacing: Float,
    val lineHeight: Float,
    val includeFontPadding: Boolean,
    val textTransform: Int,
    val breakStrategy: Int,
    val hyphenation: Int,
    val maxLines: Int,
    val fontFeatures: String?,
)

/** Marks a pressable inline run; carries the PHP handler slot. */
internal class PamSpanPress(val slot: Int) : NoCopySpan

/** React Native's `CustomLineHeightSpan`, verbatim semantics. */
internal class PamLineHeightSpan(height: Float) : LineHeightSpan {
    val height: Int = ceil(height.toDouble()).toInt()

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        // RN 0.85: half-leading is based on ascent/descent, rather than
        // top/bottom. Interior lines keep their glyph extents so tight line
        // boxes can overlap without moving the paragraph's first baseline.
        val leading = height - (fm.descent - fm.ascent)
        fm.ascent -= ceil(leading / 2.0).toInt()
        fm.descent += floor(leading / 2.0).toInt()
        if (start == 0) fm.top = fm.ascent
        if (end == text.length) fm.bottom = fm.descent
    }
}

/** Letter spacing authored in pixels, converted against the run's font size. */
internal class PamLetterSpacingSpan(private val spacingPx: Float) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) = apply(paint)

    override fun updateMeasureState(paint: TextPaint) = apply(paint)

    private fun apply(paint: TextPaint) {
        if (paint.textSize > 0f) paint.letterSpacing = spacingPx / paint.textSize
    }
}

internal class PamTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) {
        paint.typeface = typeface
    }

    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = typeface
    }
}

internal fun pamTransformText(value: String, mode: Int): String =
    when (mode) {
        2 -> value.uppercase(Locale.getDefault())
        3 -> value.lowercase(Locale.getDefault())
        4 -> value.split(PAM_WORD_BOUNDARY).joinToString(separator = "") { part ->
            if (part.firstOrNull()?.isLetter() == true) {
                part.replaceFirstChar { character -> character.titlecase(Locale.getDefault()) }
            } else {
                part
            }
        }
        else -> value
    }

private val PAM_WORD_BOUNDARY = Regex("(?<=\\s)|(?=\\s)")

/**
 * Builds text and paint exactly once for both `TextView` drawing and engine
 * measurement, following React Native's Android text pipeline: integer
 * (ceil) font pixel sizes, px letter spacing over the effective font size,
 * `CustomLineHeightSpan`, `includeFontPadding` (default true) and
 * fallback line spacing.
 */
internal object PamTextLayout {
    fun fontSizePx(logical: Float, scale: Float, density: Float): Float =
        max(1f, ceil(scaledPx(logical.coerceAtLeast(1f), scale, density).toDouble()).toFloat())

    /**
     * A font-relative length (font size, line height, letter spacing) in px,
     * like React Native's `PixelUtil.toPixelFromSP` on a style its app scaled
     * by `scale / system`: Android 14+ grows large OS scales non-linearly
     * (`FontScaleConverter`), so at 1.3 a 20 sp line height is 23.6 dp, not
     * 26 dp, and the font size follows the same curve. [scale] is the
     * effective text scale (app multiplier x capped OS scale, see
     * PamTextScale); below the non-linear threshold this is `logical x scale`.
     */
    fun scaledPx(logical: Float, scale: Float, density: Float): Float {
        if (logical == 0f) return 0f
        val system = Resources.getSystem()
        val systemScale = system.configuration.fontScale
        // Unscaled text (allowFontScaling false, or a scale of 1) is plain dp.
        if (Build.VERSION.SDK_INT < 34 || scale <= 1f || systemScale <= 1f) {
            return logical * scale * density
        }
        val metrics = system.displayMetrics
        val sp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            kotlin.math.abs(logical) * scale / systemScale,
            metrics,
        ) / metrics.density
        return (if (logical < 0f) -sp else sp) * density
    }

    fun letterSpacingEm(style: PamTextStyle, density: Float): Float {
        if (style.letterSpacing == 0f) return 0f
        return scaledPx(style.letterSpacing, style.fontScale, density) /
            fontSizePx(style.fontSize, style.fontScale, density)
    }

    fun androidBreakStrategy(strategy: Int): Int =
        when (strategy) {
            2 -> Layout.BREAK_STRATEGY_SIMPLE
            3 -> Layout.BREAK_STRATEGY_BALANCED
            else -> Layout.BREAK_STRATEGY_HIGH_QUALITY
        }

    fun androidHyphenation(frequency: Int): Int =
        when (frequency) {
            2 -> Layout.HYPHENATION_FREQUENCY_NORMAL
            3 -> Layout.HYPHENATION_FREQUENCY_FULL
            else -> Layout.HYPHENATION_FREQUENCY_NONE
        }

    /**
     * React Native wraps a text with a family, weight, italic style or font
     * features in CustomStyleSpan, which turns on subpixel text (fractional
     * glyph advances); a plain text keeps hinted integer advances. With
     * integer advances a 22-glyph line ran a few px wider and its ellipsis
     * dropped a glyph RN keeps ("hoje …" vs "hoje à…").
     */
    fun rnStyledText(family: String?, weight: Int, italic: Boolean, features: String?): Boolean =
        !family.isNullOrEmpty() || weight != 400 || italic || !features.isNullOrEmpty()

    fun configurePaint(
        paint: TextPaint,
        style: PamTextStyle,
        density: Float,
        typefaces: NativeTypefaceLoader,
    ) {
        paint.typeface = typefaces.resolve(style.fontFamily, style.fontWeight, style.italic)
        paint.isSubpixelText = rnStyledText(style.fontFamily, style.fontWeight, style.italic, style.fontFeatures)
        paint.textSize = fontSizePx(style.fontSize, style.fontScale, density)
        paint.letterSpacing = letterSpacingEm(style, density)
        paint.fontFeatureSettings = style.fontFeatures?.takeIf(String::isNotEmpty)
    }

    /** True when the plain string cannot represent the node faithfully. */
    fun needsRichText(spans: String?, lineHeight: Float): Boolean =
        !spans.isNullOrEmpty() || lineHeight > 0f

    fun content(
        raw: String,
        spansWire: String?,
        style: PamTextStyle,
        density: Float,
        typefaces: NativeTypefaceLoader,
    ): CharSequence {
        val specs = PamTextSpanSpec.parse(spansWire)
        if (specs.isEmpty() && style.lineHeight <= 0f) {
            return pamTransformText(raw, style.textTransform)
        }
        val builder = SpannableStringBuilder()
        val offsets = transformedSegments(raw, specs, style.textTransform, builder)
        val length = builder.length
        if (style.lineHeight > 0f && length >= 0) {
            builder.setSpan(
                PamLineHeightSpan(scaledPx(style.lineHeight, style.fontScale, density)),
                0,
                length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        val basePx = scaledPx(style.letterSpacing, style.fontScale, density)
        for (spec in specs) {
            val start = offsets(spec.start)
            val end = offsets(spec.end)
            if (end <= start) continue
            fun set(span: Any) = builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spec.fontSize?.let { size ->
                set(AbsoluteSizeSpan(fontSizePx(size, style.fontScale, density).toInt()))
            }
            if (spec.fontFamily != null || spec.fontWeight != null || spec.italic != null) {
                set(
                    PamTypefaceSpan(
                        typefaces.resolve(
                            spec.fontFamily ?: style.fontFamily,
                            spec.fontWeight ?: style.fontWeight,
                            spec.italic ?: style.italic,
                        ),
                    ),
                )
            }
            val spacing = spec.letterSpacing?.let { scaledPx(it, style.fontScale, density) }
                ?: basePx.takeIf { it != 0f && spec.fontSize != null }
            spacing?.let { set(PamLetterSpacingSpan(it)) }
            spec.color?.let { set(ForegroundColorSpan(it)) }
            spec.backgroundColor?.let { set(BackgroundColorSpan(it)) }
            when (spec.decoration) {
                2 -> set(UnderlineSpan())
                3 -> set(StrikethroughSpan())
                4 -> {
                    set(UnderlineSpan())
                    set(StrikethroughSpan())
                }
            }
            spec.press?.let { set(PamSpanPress(it)) }
        }
        return builder
    }

    /**
     * Appends `raw` to [builder], applying the effective text transform per
     * segment, and returns a code-point -> UTF-16 offset mapper for spans.
     */
    private fun transformedSegments(
        raw: String,
        specs: List<PamTextSpanSpec>,
        baseTransform: Int,
        builder: SpannableStringBuilder,
    ): (Int) -> Int {
        val codePoints = raw.codePointCount(0, raw.length)
        val boundaries = sortedSetOf(0, codePoints)
        specs.forEach {
            boundaries += it.start.coerceIn(0, codePoints)
            boundaries += it.end.coerceIn(0, codePoints)
        }
        val mapped = HashMap<Int, Int>(boundaries.size)
        val points = boundaries.toList()
        for (index in points.indices) {
            val point = points[index]
            mapped[point] = builder.length
            val next = points.getOrNull(index + 1) ?: break
            val from = raw.offsetByCodePoints(0, point)
            val to = raw.offsetByCodePoints(0, next)
            val transform = specs.lastOrNull {
                it.textTransform != null && it.start <= point && it.end >= next
            }?.textTransform ?: baseTransform
            builder.append(pamTransformText(raw.substring(from, to), transform))
        }
        return { point -> mapped[point.coerceIn(0, codePoints)] ?: builder.length }
    }

    /**
     * Measures like React Native's `TextLayoutManager.measureText` (0.85)
     * followed by Yoga's text rounding: the layout is built at
     * min(desired, available) whole pixels and its width is the measured
     * width, so text that wraps takes the whole available width (not its
     * widest line); heights are the bottom of the last laid-out line.
     * Returns points.
     */
    fun measure(
        raw: String,
        spansWire: String?,
        style: PamTextStyle,
        availableWidth: Float,
        density: Float,
        typefaces: NativeTypefaceLoader,
        output: FloatArray,
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.density = density
        configurePaint(paint, style, density, typefaces)
        val text = content(raw, spansWire, style, density, typefaces)
        val bounded = availableWidth.isFinite() && availableWidth > 0f
        val availablePx = if (bounded) floor(availableWidth * density + 0.001f).toInt() else Int.MAX_VALUE
        val desired = ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt()
        val layoutWidth = max(0, min(desired, availablePx))
        val layout = build(text, paint, layoutWidth, style)
        val lines = max(1, layout.lineCount)
        output[0] = layout.width / density
        output[1] = layout.getLineBottom(lines - 1) / density
        output[2] = layout.getLineBaseline(0) / density
        output[3] = lines.toFloat()
    }

    /**
     * React Native 0.85.3 TextLayoutManager.createLayout/buildLayout for a
     * text with `numberOfLines` and the tail ellipsis, at the floor of the
     * available width: a boring text that fits is a BoringLayout (no cut);
     * otherwise a StaticLayout with the same alignment, spacing, padding,
     * break strategy, hyphenation, `setEllipsize(END)` and `setMaxLines`.
     * Returns the text cut where that layout ellipsizes its last line plus
     * the ellipsis, or null when it is not truncated.
     */
    fun rnTailTruncate(
        text: CharSequence,
        paint: TextPaint,
        availableWidth: Float,
        maxLines: Int,
        includeFontPadding: Boolean,
        breakStrategy: Int,
        hyphenationFrequency: Int,
    ): CharSequence? {
        if (text.isEmpty() || maxLines <= 0 || !availableWidth.isFinite() || availableWidth <= 0f) return null
        val width = floor(availableWidth.toDouble()).toInt()
        // RN updateTextPaint: the layout paint carries the text's own base
        // size/typeface/spacing, so StaticLayout measures the ellipsis in the
        // text's font. Here those live in spans over the whole text; a view
        // paint in another face measured a wider "…" and cut a glyph early.
        val basePaint = TextPaint(paint)
        val whole = (text as? Spanned)
            ?.getSpans(0, text.length, MetricAffectingSpan::class.java)
            ?.filter { text.getSpanStart(it) <= 0 && text.getSpanEnd(it) >= text.length }
            .orEmpty()
        whole.forEach { it.updateMeasureState(basePaint) }
        val boring = BoringLayout.isBoring(text, basePaint)
        if (boring != null && boring.width <= width) return null
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, basePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(includeFontPadding)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
        val last = layout.lineCount - 1
        run {
            val src = text.toString()
            val i = src.indexOf("hoje")
            if (i >= 0) {
                val prefixes = listOf("hoje ", "hoje \u00e0", "hoje \u00e0s").map { p ->
                    val end = src.indexOf(p) + p.length
                    "$p=${StaticLayout.Builder.obtain(text, 0, end.coerceAtMost(text.length), basePaint, Int.MAX_VALUE / 4).build().getLineWidth(0)}"
                }
                android.util.Log.d(
                    "PAMPROBE2",
                    "width=$width avail=$availableWidth size=${basePaint.textSize} flags=0x${Integer.toHexString(basePaint.flags)} " +
                        "subpixel=${basePaint.isSubpixelText} linear=${basePaint.isLinearText} face=${basePaint.typeface} " +
                        "dots=${basePaint.measureText(ELLIPSIS)} $prefixes ellipsisStart=${if (last >= 0) layout.getEllipsisStart(last) else -1} " +
                        "ellipsisCount=${if (last >= 0) layout.getEllipsisCount(last) else -1} text=[$src]",
                )
            }
        }
        if (last < 0 || layout.getEllipsisCount(last) <= 0) return null
        val cut = (layout.getLineStart(last) + layout.getEllipsisStart(last)).coerceIn(0, text.length)
        val truncated = SpannableStringBuilder(text, 0, cut).append(ELLIPSIS)
        // The ellipsis is drawn in the text's own style, like RN's layout.
        if (text is Spanned) {
            for (span in text.getSpans(0, text.length, Any::class.java)) {
                if (text.getSpanStart(span) <= 0 && text.getSpanEnd(span) >= text.length && truncated.getSpanStart(span) >= 0) {
                    truncated.setSpan(span, 0, truncated.length, text.getSpanFlags(span))
                }
            }
        }
        return truncated
    }

    private const val ELLIPSIS = "\u2026"

    fun build(text: CharSequence, paint: TextPaint, width: Int, style: PamTextStyle): StaticLayout {
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(style.includeFontPadding)
            .setBreakStrategy(androidBreakStrategy(style.breakStrategy))
            .setHyphenationFrequency(androidHyphenation(style.hyphenation))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true)
        }
        if (style.maxLines > 0) {
            builder.setMaxLines(style.maxLines).setEllipsize(TextUtils.TruncateAt.END)
        }
        return builder.build()
    }
}
