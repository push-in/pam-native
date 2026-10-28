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
            assertTrue("rows are reported by index: ${request.third}", request.third.startsWith("@"))
            val (firstRow, lastRow) = request.third.removePrefix("@").split(',').map(String::toInt)
            assertEquals(WINDOW - 2, firstRow)
            assertTrue("last visible row follows the first", lastRow > firstRow)

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

    @Test
    fun rowsInsideAPlaceholderRunAreReportedAndStayPutWhenTheRunSplits() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val events = CopyOnWriteArrayList<Triple<Long, Int, String>>()
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        val rows = 10_000
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
                repeat(WINDOW) { index ->
                    mutations += create(CELL + index, LIST, index, NodeKind.COLUMN)
                    mutations += Mutation.Layout(CELL + index, Frame(0f, index * 64f, 320f, 64f))
                    mutations += text(index)
                }
                // Rows 48..9999: one placeholder node.
                mutations += run(RUN, WINDOW, rows - WINDOW)
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
                list = views(renderer)[LIST] as PamRecyclerList
                assertEquals(WINDOW + 1, list.adapter!!.itemCount)
            }
            waitFrames(4)
            instrumentation.runOnMainSync {
                // Scroll to row 5,000 (inside the run) with 10 px of it above the top.
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
                    WINDOW,
                    -(dp(list, (5_000 - WINDOW) * 64f) + 10),
                )
            }
            waitFrames(8)
            val request = events.lastOrNull { it.second == LIST_WINDOW_EVENT }
            var diagnostic = ""
            instrumentation.runOnMainSync {
                val manager = list.layoutManager as LinearLayoutManager
                diagnostic = "events=$events first=${manager.findFirstVisibleItemPosition()} " +
                    "last=${manager.findLastVisibleItemPosition()} children=${list.childCount} " +
                    "tops=${(0 until list.childCount).map { list.getChildAt(it).top to list.getChildAt(it).height }} " +
                    "rows=${list.visibleRows()?.toList()}"
            }
            assertTrue("rows inside a run must be reported: $diagnostic", request != null)
            val (firstRow, lastRow) = request!!.third.removePrefix("@").split(',').map(String::toInt)
            assertEquals(5_000, firstRow)
            assertTrue("about a screen of rows: $firstRow..$lastRow", lastRow in 5_005..5_008)

            // PHP answers: run 48..4935 (same node), rows 4936..5074, run 5075.. (new node).
            instrumentation.runOnMainSync {
                val answer = mutableListOf<Mutation>(
                    Mutation.Update(RUN, PropKey.LIST_PLACEHOLDER_ROWS, PropValue.Integer((4_936 - WINDOW).toLong())),
                    Mutation.Layout(RUN, Frame(0f, WINDOW * 64f, 320f, (4_936 - WINDOW) * 64f)),
                )
                for (index in 4_936 until 5_075) {
                    answer += create(CELL + index, LIST, index, NodeKind.COLUMN)
                    answer += Mutation.Layout(CELL + index, Frame(0f, index * 64f, 320f, 64f))
                    answer += text(index)
                }
                answer += run(RUN + 1, 5_075, rows - 5_075)
                renderer.commit(listOf(answer))
            }
            waitFrames(6)
            instrumentation.runOnMainSync {
                val top = (0 until list.childCount)
                    .map { list.getChildAt(it) }
                    .filter { row -> row.bottom > 0 }
                    .minBy { it.top }
                assertEquals("Row 5000", firstText(top))
                assertEquals(-10, top.top)
            }
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }

    private fun run(id: Long, start: Int, count: Int): List<Mutation> = listOf(
        create(id, LIST, start, NodeKind.VIEW, mapOf(
            PropKey.LIST_PLACEHOLDER to PropValue.Flag(true),
            PropKey.LIST_PLACEHOLDER_ROWS to PropValue.Integer(count.toLong()),
        )),
        Mutation.Layout(id, Frame(0f, start * 64f, 320f, count * 64f)),
    )

    private fun dp(list: View, value: Float): Int = (value * list.resources.displayMetrics.density + 0.5f).toInt()

    @Test
    fun filteringMostRowsAndReorderingKeepsTheAdapterInChildOrder() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val mutations = mutableListOf<Mutation>(
                    create(1, 0, 0, NodeKind.SCREEN),
                    create(LIST, 1, 0, NodeKind.VIRTUAL_LIST, mapOf(PropKey.LIST_ROW_HEIGHT to PropValue.Decimal(64.0))),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 400f)),
                    Mutation.Layout(LIST, Frame(0f, 0f, 320f, 400f)),
                )
                repeat(3_000) { index ->
                    mutations += create(BIG_CELL + index, LIST, index, NodeKind.COLUMN)
                    mutations += Mutation.Layout(BIG_CELL + index, Frame(0f, index * 64f, 320f, 64f))
                    mutations += bigText(index)
                }
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
                list = views(renderer)[LIST] as PamRecyclerList
            }
            waitFrames(4)
            // A filter keeps every 7th row: 2,572 removals and 428 moves in one commit.
            val kept = (0 until 3_000).filter { it % 7 == 0 }
            instrumentation.runOnMainSync {
                val mutations = mutableListOf<Mutation>()
                (0 until 3_000).filter { it % 7 != 0 }.forEach { index ->
                    mutations += Mutation.Remove(BIG_TEXT + index)
                    mutations += Mutation.Remove(BIG_CELL + index)
                }
                kept.forEachIndexed { position, index ->
                    mutations += Mutation.Move(BIG_CELL + index, LIST, position)
                    mutations += Mutation.Layout(BIG_CELL + index, Frame(0f, position * 64f, 320f, 64f))
                    mutations += Mutation.Layout(BIG_TEXT + index, Frame(8f, position * 64f + 8f, 200f, 40f))
                }
                renderer.commit(listOf(mutations))
            }
            waitFrames(4)
            instrumentation.runOnMainSync {
                assertEquals(kept.size, list.adapter!!.itemCount)
                assertEquals(kept.map { BIG_CELL + it }, (0 until kept.size).map { list.richIdAt(it) })
                val shown = (0 until list.childCount).mapNotNull { firstText(list.getChildAt(it)) }
                assertTrue("first rows show the kept items: $shown", shown.take(3) == listOf("Row 0", "Row 7", "Row 14"))
            }
            // Reversing the remaining rows (moves only) still lands in child order.
            instrumentation.runOnMainSync {
                val mutations = mutableListOf<Mutation>()
                kept.reversed().forEachIndexed { position, index ->
                    mutations += Mutation.Move(BIG_CELL + index, LIST, position)
                    mutations += Mutation.Layout(BIG_CELL + index, Frame(0f, position * 64f, 320f, 64f))
                    mutations += Mutation.Layout(BIG_TEXT + index, Frame(8f, position * 64f + 8f, 200f, 40f))
                }
                renderer.commit(listOf(mutations))
            }
            waitFrames(4)
            instrumentation.runOnMainSync {
                assertEquals(kept.reversed().map { BIG_CELL + it }, (0 until kept.size).map { list.richIdAt(it) })
            }
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }

    private fun bigText(index: Int): List<Mutation> = listOf(
        create(BIG_TEXT + index, BIG_CELL + index, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Row $index"))),
        Mutation.Layout(BIG_TEXT + index, Frame(8f, index * 64f + 8f, 200f, 40f)),
    )

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
        const val BIG_CELL = 100_000L
        const val BIG_TEXT = 200_000L
        const val RUN = 900_000L
        val LIST_WINDOW_EVENT = EventKind.LIST_WINDOW.value
    }
}
