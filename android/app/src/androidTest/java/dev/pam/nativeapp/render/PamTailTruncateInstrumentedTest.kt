package dev.pam.nativeapp.render

import android.graphics.Paint
import android.text.BoringLayout
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.floor

/**
 * Tail truncation equals React Native 0.85.3's TextLayoutManager
 * createLayout/buildLayout (StaticLayout, maxLines, TruncateAt.END at the
 * floor of the available width) by construction.
 */
@RunWith(AndroidJUnit4::class)
class PamTailTruncateInstrumentedTest {
    private fun paint(size: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size }

    /** RN 0.85.3 createLayout + buildLayout, verbatim for this case. */
    private fun reactNative(text: String, paint: TextPaint, width: Float, maxLines: Int): String {
        val layoutWidth = floor(width).toInt()
        val boring = BoringLayout.isBoring(text, paint)
        if (boring != null && boring.width <= layoutWidth) return text
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
        val last = layout.lineCount - 1
        if (layout.getEllipsisCount(last) == 0) return text
        return text.substring(0, layout.getLineStart(last) + layout.getEllipsisStart(last)) + "…"
    }

    private fun pam(text: String, paint: TextPaint, width: Float, maxLines: Int): String =
        PamTextLayout.rnTailTruncate(
            text, paint, width, maxLines, true,
            Layout.BREAK_STRATEGY_HIGH_QUALITY, Layout.HYPHENATION_FREQUENCY_NONE,
        )?.toString() ?: text

    @Test
    fun lastSeenSubtitleCutsLikeReactNative() {
        val text = "Visto por último hoje às 13:40"
        val paint = paint(32f)
        val result = pam(text, paint, 345f, 1)
        assertEquals(reactNative(text, paint, 345f, 1), result)
        assertTrue(result, result.endsWith("…"))
        assertTrue(result, result.startsWith("Visto por último"))
        // The floor of a fractional width is the width RN lays out at.
        assertEquals(reactNative(text, paint, 345.9f, 1), pam(text, paint, 345.9f, 1))
    }

    @Test
    fun twoLineCaptionKeepsRnCut() {
        val text = "Noite boa demais com a galera toda reunida 🔥🖤 depois do show ainda teve mais"
        val paint = paint(36f)
        val result = pam(text, paint, 600f, 2)
        assertEquals(reactNative(text, paint, 600f, 2), result)
        assertTrue(result, result.endsWith("…"))
    }

    @Test
    fun textThatFitsIsNotTruncated() {
        assertNull(PamTextLayout.rnTailTruncate("Online", paint(32f), 345f, 1, true, Layout.BREAK_STRATEGY_HIGH_QUALITY, Layout.HYPHENATION_FREQUENCY_NONE))
    }
}
