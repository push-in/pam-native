package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.text.PamTextProcessors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Core hooks plugins and lists build on: text processors and list fading edges. */
@RunWith(AndroidJUnit4::class)
class PamSweepHooksInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @After
    fun uninstall() = PamTextProcessors.install(null)

    /** Stands in for an EmojiCompat span: a fixed-width glyph run. */
    private class WideSpan(private val widthPx: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?) = widthPx

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) = Unit
    }

    @Test
    fun anInstalledTextProcessorShapesMeasuredAndDrawnText() {
        val density = context.resources.displayMetrics.density
        val style = PamTextStyle(
            fontFamily = null,
            fontSize = 14f,
            fontScale = 1f,
            fontWeight = 400,
            italic = false,
            letterSpacing = 0f,
            lineHeight = 0f,
            includeFontPadding = true,
            textTransform = 0,
            breakStrategy = 0,
            hyphenation = 0,
            maxLines = 0,
            fontFeatures = null,
        )
        val typefaces = NativeTypefaceLoader.shared(context)
        val plain = FloatArray(4)
        PamTextLayout.measure("a", null, style, 1_000f, density, typefaces, plain)
        PamTextProcessors.install { text ->
            SpannableString(text).apply { setSpan(WideSpan((200 * density).toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val processed = FloatArray(4)
        PamTextLayout.measure("a", null, style, 1_000f, density, typefaces, processed)
        assertTrue("measured with the processor's span: ${processed[0]}", processed[0] >= 199f && plain[0] < 50f)
        val drawn = PamTextLayout.content("a", null, style, density, typefaces)
        assertTrue("the drawn text carries the same span", (drawn as Spanned).getSpans(0, 1, WideSpan::class.java).size == 1)
    }

    @Test
    fun virtualizedListFadesItsScrollEdges() {
        instrumentation.runOnMainSync {
            val density = context.resources.displayMetrics.density
            val list = PamRecyclerList(context)
            assertFalse(list.isVerticalFadingEdgeEnabled)
            list.setFadingEdgeLengthDp(24f)
            assertTrue(list.isVerticalFadingEdgeEnabled)
            assertEquals((24 * density).toInt().toFloat(), list.verticalFadingEdgeLength.toFloat(), 1f)
            list.setHorizontal(true)
            assertTrue(list.isHorizontalFadingEdgeEnabled)
            assertFalse(list.isVerticalFadingEdgeEnabled)
            list.setFadingEdgeLengthDp(0f)
            assertFalse(list.isHorizontalFadingEdgeEnabled)
        }
    }
}
