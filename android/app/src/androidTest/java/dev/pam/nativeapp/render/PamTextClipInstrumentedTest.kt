package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zé chat header avatar: a 36 dp circle (border 1.5, overflow hidden) with a
 * centered bold "QA". The second glyph must never be clipped (RN shows "QA").
 */
@RunWith(AndroidJUnit4::class)
class PamTextClipInstrumentedTest {
    @Test
    fun centeredAvatarInitialsKeepEveryGlyphAtAnyFractionalPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val density = activity.resources.displayMetrics.density
                val output = FloatArray(4)
                PamTextLayout.measure(
                    raw = "QA",
                    spansWire = null,
                    style = PamTextStyle(
                        fontFamily = null,
                        fontSize = 13f,
                        fontScale = 1f,
                        fontWeight = 700,
                        italic = false,
                        letterSpacing = 0f,
                        lineHeight = 0f,
                        includeFontPadding = true,
                        textTransform = 0,
                        breakStrategy = 0,
                        hyphenation = 0,
                        maxLines = 0,
                        fontFeatures = null,
                    ),
                    availableWidth = 33f,
                    density = density,
                    typefaces = NativeTypefaceLoader.shared(activity),
                    output = output,
                )
                val textWidth = output[0]
                val textHeight = output[1]
                val measuredPx = kotlin.math.ceil(textWidth * density - 0.01f).toInt()
                // Find an avatar position whose edges round one pixel apart
                // (the device-specific case behind "QA" rendering as "Q").
                var avatarX = 16f
                for (step in 0 until 40_000) {
                    val candidate = 16f + step * 0.00731f
                    val textX = candidate + 1.5f + (33f - textWidth) / 2f
                    if (snappedPixelSpan(textX, textWidth, candidate, density).extent < measuredPx) {
                        avatarX = candidate
                        break
                    }
                }
                val textX = avatarX + 1.5f + (33f - textWidth) / 2f
                val textY = 20f + 1.5f + (33f - textHeight) / 2f
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF1B7A4EL),
                        PropKey.BORDER_WIDTH to PropValue.Decimal(1.5),
                        PropKey.BORDER_COLOR to PropValue.Integer(0xFF0F1410L),
                        PropKey.BORDER_RADIUS to PropValue.Decimal(18.0),
                        PropKey.OVERFLOW to PropValue.Integer(2),
                        PropKey.TEST_ID to PropValue.Text("avatar"),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.TEXT, mapOf(
                        PropKey.TEXT to PropValue.Text("QA"),
                        PropKey.FONT_SIZE to PropValue.Decimal(13.0),
                        PropKey.FONT_WEIGHT to PropValue.Integer(700),
                        PropKey.TEXT_COLOR to PropValue.Integer(0xFFFFD23FL),
                        PropKey.TEST_ID to PropValue.Text("avatar-initials"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(avatarX, 20f, 36f, 36f)),
                    Mutation.Layout(3, Frame(textX, textY, textWidth, textHeight)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val avatar = requireNotNull(activity.host.findByTransitionName("avatar"))
                val initials = requireNotNull(activity.host.findByTransitionName("avatar-initials")) as TextView
                assertEquals("initials must fit on one line", 1, initials.layout.lineCount)
                assertEquals(2, initials.layout.getLineEnd(0))
                val bitmap = Bitmap.createBitmap(avatar.width, avatar.height, Bitmap.Config.ARGB_8888)
                avatar.draw(Canvas(bitmap))
                // Glyph pixels in the right half belong to the "A".
                var rightGlyphPixels = 0
                for (x in avatar.width / 2 until avatar.width) {
                    for (y in 0 until avatar.height) {
                        val pixel = bitmap.getPixel(x, y)
                        if (Color.red(pixel) > 200 && Color.green(pixel) > 160 && Color.blue(pixel) < 120) {
                            rightGlyphPixels++
                        }
                    }
                }
                assertTrue("second glyph drawn ($rightGlyphPixels px)", rightGlyphPixels > 20)
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun boldLabelsInFixedWidthCardsAreNeverEllipsized() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        // Reaction sheet cards (11 px / 800) and the media viewer action
        // (Salvar / 600), each single-line with tail ellipsis like RN.
        val labels = listOf(
            Triple("Curtir", 11f, 800),
            Triple("Rir", 11f, 800),
            Triple("Triste", 11f, 800),
            Triple("Salvar", 14f, 600),
            Triple("Responder", 13f, 700),
        )
        lateinit var renderer: PamRenderer
        try {
            onMain(instrumentation) {
                val density = activity.resources.displayMetrics.density
                val mutations = mutableListOf<Mutation>(Mutation.Create(node(1, 0, NodeKind.SCREEN)))
                val layouts = mutableListOf<Mutation>(Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)))
                labels.forEachIndexed { index, (label, size, weight) ->
                    val style = PamTextStyle(
                        fontFamily = null,
                        fontSize = size,
                        fontScale = 1f,
                        fontWeight = weight,
                        italic = false,
                        letterSpacing = 0f,
                        lineHeight = 0f,
                        includeFontPadding = true,
                        textTransform = 0,
                        breakStrategy = 0,
                        hyphenation = 0,
                        maxLines = 1,
                        fontFeatures = null,
                    )
                    val output = FloatArray(4)
                    PamTextLayout.measure(
                        raw = label,
                        spansWire = null,
                        style = style,
                        availableWidth = 76f,
                        density = density,
                        typefaces = NativeTypefaceLoader.shared(activity),
                        output = output,
                    )
                    val width = output[0]
                    val measuredPx = kotlin.math.ceil(width * density - 0.01f).toInt()
                    var cardX = 4f
                    for (step in 0 until 40_000) {
                        val candidate = 4f + step * 0.00731f
                        val textX = candidate + (76f - width) / 2f
                        if (snappedPixelSpan(textX, width, candidate, density).extent < measuredPx) {
                            cardX = candidate
                            break
                        }
                    }
                    val card = 10L + index * 2
                    val text = card + 1
                    val top = 20f + index * 40f
                    mutations += Mutation.Create(NodeSpec(card, 1, index, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2L),
                        PropKey.OVERFLOW to PropValue.Integer(2),
                    )))
                    mutations += Mutation.Create(node(text, card, NodeKind.TEXT, mapOf(
                        PropKey.TEXT to PropValue.Text(label),
                        PropKey.FONT_SIZE to PropValue.Decimal(size.toDouble()),
                        PropKey.FONT_WEIGHT to PropValue.Integer(weight.toLong()),
                        PropKey.NUMBER_OF_LINES to PropValue.Integer(1),
                        PropKey.TEXT_COLOR to PropValue.Integer(0xFF0F1410L),
                        PropKey.TEST_ID to PropValue.Text("label-$label"),
                    )))
                    layouts += Mutation.Layout(card, Frame(cardX, top, 76f, 32f))
                    layouts += Mutation.Layout(
                        text,
                        Frame(cardX + (76f - width) / 2f, top + (32f - output[1]) / 2f, width, output[1]),
                    )
                }
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(mutations + layouts + Mutation.SetRoot(1)))
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                for ((label) in labels) {
                    val view = requireNotNull(activity.host.findByTransitionName("label-$label")) as TextView
                    val layout = requireNotNull(view.layout)
                    assertEquals(
                        "'$label' must not be ellipsized (w=${view.width} pad=${view.paddingLeft},${view.paddingRight} " +
                            "desired=${android.text.Layout.getDesiredWidth(label, view.paint)} " +
                            "flags=${view.paintFlags} size=${view.paint.textSize} ls=${view.paint.letterSpacing} " +
                            "fake=${view.paint.isFakeBoldText} tf=${view.typeface?.weight} layoutW=${layout.width})",
                        0,
                        layout.getEllipsisCount(0),
                    )
                    assertEquals("'$label' must fit one line", label.length, layout.getLineEnd(0))
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    var inkInLastQuarter = 0
                    for (x in view.width * 3 / 4 until view.width) {
                        for (y in 0 until view.height) {
                            if (Color.alpha(bitmap.getPixel(x, y)) > 128) inkInLastQuarter++
                        }
                    }
                    assertTrue("'$label' last glyph drawn", inkInLastQuarter > 4)
                }
                renderer.close()
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun View.findByTransitionName(name: String): View? {
        if (transitionName == name) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findByTransitionName(name)?.let { return it }
        }
        return null
    }
}
