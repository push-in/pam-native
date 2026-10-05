package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text boxes are sized by PamTextLayout (the engine's host measurer) and drawn
 * by TextView; both must agree to the pixel, following React Native Android.
 */
@RunWith(AndroidJUnit4::class)
class PamTextParityInstrumentedTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density
    private val typefaces = NativeTypefaceLoader.shared(instrumentation.targetContext)

    private fun style(
        size: Float = 15f,
        weight: Int = 400,
        letterSpacing: Float = 0f,
        lineHeight: Float = 0f,
        pad: Boolean = true,
        maxLines: Int = 0,
    ) = PamTextStyle(null, size, 1f, weight, false, letterSpacing, lineHeight, pad, 1, 1, 1, maxLines, null)

    private fun properties(text: String, style: PamTextStyle, spans: String? = null): Map<PropKey, PropValue> =
        buildMap {
            put(PropKey.TEXT, PropValue.Text(text))
            put(PropKey.FONT_SIZE, PropValue.Decimal(style.fontSize.toDouble()))
            put(PropKey.FONT_WEIGHT, PropValue.Integer(style.fontWeight.toLong()))
            put(PropKey.TEXT_COLOR, PropValue.Integer(0xFF000000))
            if (style.letterSpacing != 0f) put(PropKey.LETTER_SPACING, PropValue.Decimal(style.letterSpacing.toDouble()))
            if (style.lineHeight > 0f) put(PropKey.LINE_HEIGHT, PropValue.Decimal(style.lineHeight.toDouble()))
            if (!style.includeFontPadding) put(PropKey.INCLUDE_FONT_PADDING, PropValue.Flag(false))
            if (style.maxLines > 0) put(PropKey.NUMBER_OF_LINES, PropValue.Integer(style.maxLines.toLong()))
            spans?.let { put(PropKey.TEXT_SPANS, PropValue.Text(it)) }
        }

    @Test
    fun engineMeasurementMatchesTheRenderedTextView() {
        val cases = listOf(
            "Zé Chat" to style(),
            "Mensagem longa que precisa quebrar em várias linhas dentro do balão de conversa" to style(size = 14f),
            "Bold heading wraps nicely across two lines" to style(size = 22f, weight = 700),
            "Tracking wide letters" to style(size = 13f, letterSpacing = 1.2f),
            "Line height twenty with several wrapped lines of text inside" to style(size = 15f, lineHeight = 20f),
            "Tight line height smaller than the font metrics wraps" to style(size = 18f, lineHeight = 16f),
            "No font padding single line" to style(pad = false),
            "Clamped to two lines even though this text is far too long to fit in two" to style(maxLines = 2),
            "Emoji 😀👍🏽 and acentuação" to style(size = 16f),
        )
        val output = FloatArray(4)
        for ((text, style) in cases) {
            PamTextLayout.measure(text, null, style, 200f, density, typefaces, output)
            val (width, height, _, lines) = output.toList()
            assertTrue("$text width ${output[0]} must fit", width <= 200f + 0.001f)
            renderText(properties(text, style), width, height) { view ->
                val layout = view.layout
                assertEquals("$text line count", lines.toInt(), layout.lineCount)
                assertEquals("$text height", Math.round(height * density), layout.height + view.totalPaddingTop + view.totalPaddingBottom)
                for (line in 0 until layout.lineCount) {
                    assertTrue("$text line $line must fit its box", layout.getLineMax(line) <= view.width - view.totalPaddingLeft - view.totalPaddingRight + 0.5f)
                }
                assertEquals(style.includeFontPadding, view.includeFontPadding)
            }
        }
    }

    @Test
    fun richSpansDrawExactlyLikeTheReactNativePipelineAndDispatchPresses() {
        val text = "Olá mundo https://zé.chat e @ana!"
        val spans = "4,25,,700;10,25,,700,,4279991118,,2,,,0;28,32,13,,,4278211071,4293848814,,,,1"
        val style = style(size = 15f, lineHeight = 20f)
        val output = FloatArray(4)
        PamTextLayout.measure(text, spans, style, 220f, density, typefaces, output)
        val events = mutableListOf<Pair<Int, String>>()
        renderText(
            properties(text, style, spans) + (PropKey.ON_SPAN_PRESS to PropValue.Flag(true)),
            output[0],
            output[1],
            onEvent = { kind, payload -> events += kind to payload },
        ) { view ->
            val spanned = view.text as Spanned
            assertTrue(spanned.getSpans(0, spanned.length, UnderlineSpan::class.java).isNotEmpty())
            assertTrue(spanned.getSpans(0, spanned.length, AbsoluteSizeSpan::class.java).isNotEmpty())
            assertEquals(2, spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java).size)

            val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(actual))
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                this.density = this@PamTextParityInstrumentedTest.density
                color = Color.BLACK
            }
            PamTextLayout.configurePaint(paint, style, density, typefaces)
            val content = PamTextLayout.content(text, spans, style, density, typefaces)
            val reference = PamTextLayout.build(content, paint, view.width, style)
            val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            reference.draw(Canvas(expected))
            assertTrue("TextView pixels must equal the StaticLayout reference", actual.sameAs(expected))

            val mentionOffset = content.toString().indexOf("@ana") + 1
            val line = view.layout.getLineForOffset(mentionOffset)
            val x = view.layout.getPrimaryHorizontal(mentionOffset) + view.totalPaddingLeft + 1f
            val y = (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f + view.totalPaddingTop
            val now = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
            view.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0))
        }
        assertEquals(listOf(EventKind.SPAN_PRESS.value to "1"), events)
    }

    @Test
    fun includeFontPaddingDefaultsToReactNativeAndOptsOut() {
        renderText(properties("Aa", style()), 100f, 40f) { view -> assertTrue(view.includeFontPadding) }
        renderText(properties("Aa", style(pad = false)), 100f, 40f) { view -> assertFalse(view.includeFontPadding) }
    }

    @Test
    fun onLayoutReportsTheFrameRelativeToItsParentOnce() {
        val events = mutableListOf<Pair<Int, ByteArray>>()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            lateinit var renderer: PamRenderer
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, kind, payload -> events += kind to payload }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                            Mutation.Create(NodeSpec(2, 1, 0, NodeKind.VIEW, emptyMap())),
                            Mutation.Create(NodeSpec(3, 2, 0, NodeKind.VIEW, mapOf(PropKey.ON_LAYOUT to PropValue.Flag(true)))),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(10f, 20f, 300f, 300f)),
                            Mutation.Layout(3, Frame(15.5f, 32f, 100f, 40.25f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                renderer.commit(listOf(listOf(Mutation.Layout(3, Frame(15.5f, 32f, 100f, 40.25f)))))
            }
            instrumentation.waitForIdleSync()
            val layouts = events.filter { it.first == EventKind.LAYOUT.value }
            assertEquals(1, layouts.size)
            val values = dev.pam.nativeapp.protocol.WireMap.decode(layouts.single().second)
            assertEquals(5.5, (values["x"] as dev.pam.nativeapp.protocol.WireValue.Decimal).value, 0.001)
            assertEquals(12.0, (values["y"] as dev.pam.nativeapp.protocol.WireValue.Decimal).value, 0.001)
            assertEquals(40.25, (values["height"] as dev.pam.nativeapp.protocol.WireValue.Decimal).value, 0.001)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun renderText(
        properties: Map<PropKey, PropValue>,
        width: Float,
        height: Float,
        onEvent: (Int, String) -> Unit = { _, _ -> },
        assertions: (TextView) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            instrumentation.runOnMainSync {
                val renderer = PamRenderer(activity, activity.host) { _, kind, payload ->
                    onEvent(kind, String(payload, Charsets.UTF_8))
                }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                            Mutation.Create(NodeSpec(2, 1, 0, NodeKind.TEXT, properties)),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(10.3f, 20f, width, height)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertions((activity.host.getChildAt(0) as ViewGroup).getChildAt(0) as TextView)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
