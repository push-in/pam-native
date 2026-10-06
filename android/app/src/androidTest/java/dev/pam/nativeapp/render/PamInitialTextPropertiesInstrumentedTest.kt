package dev.pam.nativeapp.render

import android.content.Context
import android.content.Intent
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
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
import java.util.EnumMap

/** Counts platform setText calls during real virtual-list materialization. */
@RunWith(AndroidJUnit4::class)
class PamInitialTextPropertiesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun cellContentIsBuiltOnceAndIncrementalChangesRemainImmediate() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var texts: List<CountingTextView>
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                texts = List(2) { CountingTextView(activity) }
                // The renderer's existing pool supplies the counting views;
                // both cells still use the production adapter and mount path.
                pool(renderer)[PrewarmPool.TEXT] = ArrayDeque<View>().apply { addAll(texts) }
                val mutations = mutableListOf<Mutation>(
                    create(1, 0, NodeKind.SCREEN),
                    create(2, 1, NodeKind.VIRTUAL_LIST),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 240f)),
                    Mutation.Layout(2, Frame(0f, 0f, 320f, 240f)),
                )
                texts.forEachIndexed { index, text ->
                    text.assignments = 0
                    val properties = properties()
                    // TEXT may precede or follow its style/span properties.
                    val ordered = if (index == 0) properties else properties.entries
                        .reversed().associateTo(linkedMapOf()) { it.toPair() }
                    mutations += create(10L + index, 2, NodeKind.VIEW, index = index)
                    mutations += create(20L + index, 10L + index, NodeKind.TEXT, ordered)
                    mutations += Mutation.Layout(10L + index, Frame(0f, index * 80f, 320f, 80f))
                    mutations += Mutation.Layout(20L + index, Frame(8f, 8f, 260f, 64f))
                }
                mutations += Mutation.SetRoot(1)
                renderer.commit(listOf(mutations))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                texts.forEach { text ->
                    assertEquals("one content build per initially mounted cell", 1, text.assignments)
                    assertEquals("Hello link", text.text.toString())
                    val content = text.text as Spanned
                    assertEquals(1, content.getSpans(0, content.length, PamLineHeightSpan::class.java).size)
                    assertEquals(1, content.getSpans(0, content.length, ForegroundColorSpan::class.java).size)
                    assertEquals(1, content.getSpans(0, content.length, PamSpanPress::class.java).size)
                    assertTrue(text.typeface.isBold)
                    // The existing SDK/RN text pipeline rounds scaled text
                    // sizes up to whole pixels (17 * 2.625 becomes 45).
                    assertEquals(
                        PamTextLayout.fontSizePx(17f, 1f, activity.resources.displayMetrics.density),
                        text.textSize,
                        0f,
                    )
                }
                val first = texts.first()
                val initialTextSize = first.textSize
                renderer.commit(listOf(listOf(Mutation.Update(20, PropKey.TEXT, PropValue.Text("Other link")))))
                assertEquals(2, first.assignments)
                assertEquals("Other link", first.text.toString())
                renderer.commit(listOf(listOf(Mutation.Update(20, PropKey.LINE_HEIGHT, PropValue.Decimal(28.0)))))
                assertEquals(3, first.assignments)
                renderer.commit(listOf(listOf(Mutation.Update(20, PropKey.FONT_SIZE, PropValue.Decimal(20.0)))))
                assertEquals(4, first.assignments)
                assertEquals(
                    PamTextLayout.fontSizePx(20f, 1f, activity.resources.displayMetrics.density),
                    first.textSize,
                    0f,
                )
                // A real size change followed by restoring 17 exercises the
                // original incremental path, outside initialization batching.
                renderer.commit(listOf(listOf(Mutation.Update(20, PropKey.FONT_SIZE, PropValue.Decimal(17.0)))))
                assertEquals(5, first.assignments)
                assertEquals(initialTextSize, first.textSize, 0f)
                assertEquals("unmodified second cell", 1, texts.last().assignments)
            }
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun properties() = linkedMapOf(
        PropKey.TEXT to PropValue.Text("Hello link"),
        PropKey.FONT_SIZE to PropValue.Decimal(17.0),
        PropKey.FONT_FAMILY to PropValue.Text("sans-serif"),
        PropKey.FONT_WEIGHT to PropValue.Integer(700),
        PropKey.LINE_HEIGHT to PropValue.Decimal(23.0),
        PropKey.TEXT_SPANS to PropValue.Text("6,10,,,,4278211071,,,,,1"),
        PropKey.ON_SPAN_PRESS to PropValue.Flag(true),
        PropKey.TEXT_ALLOW_FONT_SCALING to PropValue.Flag(false),
        PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER to PropValue.Decimal(1.0),
    )

    private class CountingTextView(context: Context) : TextView(context) {
        var assignments = 0
        override fun setText(text: CharSequence?, type: BufferType?) {
            assignments++
            super.setText(text, type)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pool(renderer: PamRenderer): EnumMap<PrewarmPool, ArrayDeque<View>> =
        PamRenderer::class.java.getDeclaredField("prewarmedViews").apply { isAccessible = true }
            .get(renderer) as EnumMap<PrewarmPool, ArrayDeque<View>>

    private fun create(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
        index: Int = 0,
    ) = Mutation.Create(NodeSpec(id, parent, index, kind, properties))
}
