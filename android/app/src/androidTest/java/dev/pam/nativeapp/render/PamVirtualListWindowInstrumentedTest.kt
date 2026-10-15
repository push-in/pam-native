package dev.pam.nativeapp.render

import android.content.Intent
import android.util.LongSparseArray
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Windowed lists (1.44.0): rows outside PHP's window arrive as childless
 * placeholders. Native reports the visible rows (EventKind ListWindow = 80)
 * only when placeholders come near, and a later commit that materializes a
 * bound placeholder shows its content without a scroll.
 */
@RunWith(AndroidJUnit4::class)
class PamVirtualListWindowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun placeholdersNearTheViewportRequestTheirRowsAndMaterializeInPlace() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val events = CopyOnWriteArrayList<Triple<Long, Int, String>>()
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    events.add(Triple(id, kind, String(payload, Charsets.UTF_8)))
                }
                val mutations = mutableListOf<Mutation>(
                    create(1, 0, 0, NodeKind.SCREEN),
                    create(LIST, 1, 0, NodeKind.VIRTUAL_LIST, mapOf(
                        PropKey.LIST_ROW_HEIGHT to PropValue.Decimal(64.0),
                        PropKey.LIST_WINDOWED to PropValue.Flag(true),
                    )),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 400f)),
                    Mutation.Layout(LIST, Frame(0f, 0f, 320f, 400f)),
                )
                repeat(COUNT) { index ->
                    val materialized = index < WINDOW
                    mutations += create(
                        CELL + index,
                        LIST,
                        index,
                        NodeKind.COLUMN,
                        if (materialized) emptyMap() else mapOf(PropKey.LIST_PLACEHOLDER to PropValue.Flag(true)),
                    )
                    mutations += Mutation.Layout(CELL + index, Frame(0f, index * 64f, 320f, 64f))
                    if (materialized) mutations += text(index)
                }
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
                list = views(renderer)[LIST] as PamRecyclerList
            }
            waitFrames(8)
            assertTrue(
                "no placeholder near the first rows: no request",
                events.none { it.second == LIST_WINDOW_EVENT },
            )

            instrumentation.runOnMainSync {
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(WINDOW - 2, 0)
            }
            waitFrames(8)
            val request = events.lastOrNull { it.second == LIST_WINDOW_EVENT }
            assertTrue("rows near placeholders must be reported", request != null)
            assertEquals(LIST, request!!.first)
            val (firstId, lastId) = request.third.split(',').map(String::toLong)
            assertEquals(CELL + WINDOW - 2, firstId)
            assertTrue("last visible row follows the first", lastId > firstId)

            val requests = events.count { it.second == LIST_WINDOW_EVENT }
            waitFrames(4)
            assertEquals(
                "an unanswered identical range is not re-sent every frame",
                requests,
                events.count { it.second == LIST_WINDOW_EVENT },
            )

            // PHP answers: the placeholder rows on screen get their content.
            instrumentation.runOnMainSync {
                val answer = mutableListOf<Mutation>()
                for (index in WINDOW until WINDOW + 8) {
                    answer += Mutation.Update(CELL + index, PropKey.LIST_PLACEHOLDER, null)
                    answer += text(index)
                }
                renderer.commit(listOf(answer))
            }
            waitFrames(6)
            instrumentation.runOnMainSync {
                val shown = (0 until list.childCount)
                    .map { list.getChildAt(it) as ViewGroup }
                    .filter { row -> row.bottom > 0 && row.top < list.height }
                    .mapNotNull { row -> firstText(row) }
                assertTrue("materialized rows show their text: $shown", "Row $WINDOW" in shown)
            }
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }

    private fun text(index: Int): List<Mutation> = listOf(
        create(TEXT + index, CELL + index, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Row $index"))),
        Mutation.Layout(TEXT + index, Frame(8f, index * 64f + 8f, 200f, 40f)),
    )

    private fun firstText(view: View): String? = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { firstText(view.getChildAt(it)) }
        else -> null
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            assertTrue("frame callback", latch.await(2, TimeUnit.SECONDS))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): LongSparseArray<View> = PamRenderer::class.java
        .getDeclaredField("views").apply { isAccessible = true }.get(renderer) as LongSparseArray<View>

    private fun create(id: Long, parent: Long, index: Int, kind: NodeKind, props: Map<PropKey, PropValue> = emptyMap()) =
        Mutation.Create(NodeSpec(id, parent, index, kind, props))

    private companion object {
        const val LIST = 2L
        const val COUNT = 200
        const val WINDOW = 48
        const val CELL = 100L
        const val TEXT = 1000L
        val LIST_WINDOW_EVENT = EventKind.LIST_WINDOW.value
    }
}
