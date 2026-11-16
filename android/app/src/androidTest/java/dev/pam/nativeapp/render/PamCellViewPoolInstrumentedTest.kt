package dev.pam.nativeapp.render

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.recyclerview.widget.RecyclerView
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Views of list cells scrolled out are reused by cells of the same shape
 * (`PamRenderer.cellViewPool`). Text nodes are PamTextViews: they are reused,
 * and a reused view carries nothing of the cell it showed before.
 */
@RunWith(AndroidJUnit4::class)
class PamCellViewPoolInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var renderer: PamRenderer
    private lateinit var list: PamRecyclerList

    /** Text node id -> row index. */
    private val textRows = LinkedHashMap<Long, Int>()

    /** Node ids each text view showed while the list moved. */
    private val hosted = IdentityHashMap<View, MutableSet<Long>>()
    private val events = ArrayList<Pair<Long, Int>>()
    private val stray = ArrayList<String>()

    @Before fun start() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        main {
            renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                events += id to kind
                // A text event always comes from the node's own view.
                if ((kind == EventKind.TEXT_LAYOUT.value || kind == EventKind.SPAN_PRESS.value) &&
                    renderer.viewForNode(id) == null
                ) {
                    stray += "event $kind for node $id, which has no view"
                }
            }
        }
    }

    @After fun stop() {
        main {
            renderer.close()
            activity.finish()
        }
    }

    @Test fun recycledTextRowsReuseTheirTextViews() {
        mount(ROWS, 64f) { i, y, out ->
            row(i, y, out, mapOf(PropKey.TEXT to PropValue.Text("Message $i")))
        }
        scrollThrough()
        main {
            val texts = hosted.keys.filterIsInstance<PamTextView>()
            assertTrue("a text view showed several rows", texts.any { hosted.getValue(it).size > 1 })
            assertTrue("a long scroll reuses text views (${texts.size} for $ROWS rows)", texts.size < ROWS / 2)
            forEachMountedText { i, view -> assertEquals("Message $i", view.text.toString()) }
        }
    }

    @Test fun aReusedTextViewKeepsNoSpanLinkSelectionOrAccessibilityStateOfItsPreviousRow() {
        // One shape (the same property keys) for every row; even rows carry
        // a press span, a link, selection and an accessibility action,
        // odd rows the "empty" values.
        mount(ROWS, 64f) { i, y, out ->
            val linked = i % 2 == 0
            row(i, y, out, mapOf(
                PropKey.TEXT to PropValue.Text(if (linked) "Link https://pam.dev/$i" else "Plain $i"),
                PropKey.TEXT_SPANS to PropValue.Text(if (linked) "0,4,,,,4278211071,,,,,1" else ""),
                PropKey.ON_SPAN_PRESS to PropValue.Flag(true),
                PropKey.ON_TEXT_LAYOUT to PropValue.Flag(true),
                PropKey.TEXT_DATA_DETECTOR_TYPE to PropValue.Integer(if (linked) 3 else 1),
                PropKey.TEXT_SELECTABLE to PropValue.Flag(linked),
                PropKey.SELECTION_COLOR to PropValue.Integer((if (linked) RED else BLUE).toLong()),
            ), cell = mapOf(
                PropKey.ON_PRESS to PropValue.Flag(true),
                PropKey.ACCESSIBILITY_ACTIONS to PropValue.Text(if (linked) """[{"name":"archive","label":"Archive"}]""" else "[]"),
                PropKey.ON_ACCESSIBILITY_ACTION to PropValue.Flag(true),
            ))
        }
        // Accessibility importance of rows on their first (new) views.
        val freshImportance = read {
            listOf(0, 1).map { i -> renderer.viewForNode(CELL + i)!!.importantForAccessibility to renderer.viewForNode(TEXT + i)!!.importantForAccessibility }
        }
        scrollThrough()
        var crossed = 0L
        main {
            crossed = textRows.keys.firstOrNull { id ->
                textRows.getValue(id) % 2 == 1 &&
                    renderer.viewForNode(id)?.let { view -> hosted[view]?.any { textRows.getValue(it) % 2 == 0 } } == true
            } ?: 0L
            assertTrue("a row without link shows a view that had one", crossed != 0L)
            forEachMountedText { i, view ->
                val linked = i % 2 == 0
                val label = "row $i"
                assertEquals(label, if (linked) "Link https://pam.dev/$i" else "Plain $i", view.text.toString())
                val links = (view.text as? Spanned)?.getSpans(0, view.text.length, URLSpan::class.java)?.size ?: 0
                assertEquals("$label links", linked, links > 0)
                assertEquals("$label selectable", linked, view.isTextSelectable)
                if (!linked) {
                    assertNull("$label movement", view.movementMethod)
                    assertEquals("$label links clickable", false, view.linksClickable)
                }
                assertEquals("$label selection colour", if (linked) RED else BLUE, view.highlightColor)
                val (row, text) = freshImportance[i % 2]
                assertEquals("$label row accessibility importance", row, renderer.viewForNode(CELL + i)?.importantForAccessibility)
                assertEquals("$label text accessibility importance", text, view.importantForAccessibility)
            }
        }
        main { assertEquals("events from views of other rows", emptyList<String>(), stray.toList()) }
        // Span presses report the row's own node, and only rows with spans.
        main {
            events.clear()
            val expected = HashSet<Long>()
            for ((id, row) in textRows) {
                val view = renderer.viewForNode(id) as? PamTextView ?: continue
                if (tapFirstCharacter(view) && row % 2 == 0) expected += id
            }
            val pressed = events.filter { it.second == EventKind.SPAN_PRESS.value }.map { it.first }.toSet()
            assertTrue("rows with spans were tapped", expected.isNotEmpty())
            assertEquals("span presses", expected, pressed)
        }
        // Dropping selectionColor restores the platform colour, not the previous row's.
        commit(Mutation.Update(crossed, PropKey.SELECTION_COLOR, null))
        main {
            val fresh = PamTextView(activity).highlightColor
            assertEquals("platform selection colour", fresh, (renderer.viewForNode(crossed) as PamTextView).highlightColor)
        }
        waitFrames(4)
        main { assertEquals("events from views of other rows", emptyList<String>(), stray.toList()) }
    }

    @Test fun clockAndRevealRowsShowTheirOwnTimeAfterScrollingAwayAndBack() {
        // Clock rows, typewriter rows and plain rows: three shapes, each
        // reusing views of its own kind.
        mount(ROWS, 64f) { i, y, out ->
            row(i, y, out, when (i % 3) {
                0 -> mapOf(
                    PropKey.TEXT to PropValue.Text("--:--"),
                    PropKey.CLOCK_OFFSET_MS to PropValue.Integer(i * 1_000L),
                    PropKey.CLOCK_RUNNING to PropValue.Flag(false),
                    PropKey.CLOCK_FORMAT to PropValue.Integer(PamClockFormat.PADDED_MINUTES_SECONDS.toLong()),
                )
                1 -> mapOf(
                    PropKey.TEXT to PropValue.Text("Reveal $i"),
                    PropKey.REVEAL_CHAR_MS to PropValue.Integer(4),
                    PropKey.REVEAL_KEY to PropValue.Text("k$i"),
                )
                else -> mapOf(PropKey.TEXT to PropValue.Text("Plain $i"))
            })
        }
        scrollThrough()
        main {
            assertTrue(
                "clock views were reused by other clock rows",
                hosted.values.any { ids -> ids.count { textRows.getValue(it) % 3 == 0 } > 1 },
            )
            forEachMountedText { i, view ->
                val binding = PamTimeBinding.of(view)
                val label = "row $i"
                when (i % 3) {
                    0 -> {
                        val clock = clockLabel(i)
                        assertEquals(label, clock, view.text.toString())
                        assertEquals(label, clock, view.nativeText)
                        assertNotNull(label, binding?.clock)
                        assertNull(label, binding?.reveal)
                    }
                    1 -> {
                        assertEquals(label, "Reveal $i", view.text.toString())
                        assertNull(label, view.nativeText)
                        assertEquals(label, "k$i", binding?.reveal?.key)
                        assertNull(label, binding?.clock)
                    }
                    else -> {
                        assertEquals(label, "Plain $i", view.text.toString())
                        assertNull(label, binding)
                        assertNull(label, view.nativeText)
                        assertEquals(label, -1, view.revealEnd)
                    }
                }
            }
        }
        await("every typewriter row on screen finishes its text") {
            var done = true
            forEachMountedText { i, view -> if (i % 3 == 1 && onScreen(view) && view.revealEnd != -1) done = false }
            done
        }
    }

    @Test fun aListsFirstFrameBindsOnlyTheRowsInItsViewport() {
        var firstFrame: List<View>? = null
        var listHeight = 0
        val listener = ViewTreeObserver.OnPreDrawListener {
            val list = renderer.viewForNode(2) as? PamRecyclerList
            if (firstFrame == null && list != null && list.childCount > 0) {
                firstFrame = (0 until list.childCount).map(list::getChildAt)
                listHeight = list.height
            }
            true
        }
        main { activity.host.viewTreeObserver.addOnPreDrawListener(listener) }
        try {
            mount(ROWS, 64f) { i, y, out -> row(i, y, out, mapOf(PropKey.TEXT to PropValue.Text("Message $i"))) }
        } finally {
            main { activity.host.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val rows = requireNotNull(firstFrame) { "the list drew" }
        assertTrue("the viewport is filled", rows.any { it.bottom >= listHeight })
        assertEquals(
            "rows bound past the viewport in the list's first frame",
            emptyList<Int>(),
            rows.filter { it.top >= listHeight }.map { it.top },
        )
        // The rows ahead follow from the next frames, mounted.
        await("the row past the viewport is bound and mounted") {
            (0 until list.childCount).map(list::getChildAt).any { row ->
                row.top >= list.height && (row as android.view.ViewGroup).childCount > 0
            }
        }
    }

    /**
     * A long fling through chat-like rows (pressable, avatar, name, message,
     * time, badge). Logs the frame timings and the text views it created;
     * with `adb shell setprop log.tag.PamCellPerf DEBUG` the renderer also
     * logs each row's bind cost.
     */
    @Test fun aLongFlingBindsRowsFromReusedTextViews() {
        mount(FLING_ROWS, 72f) { i, y, out -> chatRow(i, y, out) }
        val frames = ArrayList<LongArray>()
        val thread = HandlerThread("pam-cell-pool-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(frames) {
                frames += longArrayOf(
                    metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                )
            }
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        val started = SystemClock.uptimeMillis()
        try {
            for (direction in intArrayOf(1, -1)) {
                var flings = 0
                while (read { list.canScrollVertically(direction) } && flings++ < 60) {
                    main { list.fling(0, direction * FLING_VELOCITY) }
                    while (read { list.scrollState != RecyclerView.SCROLL_STATE_IDLE }) {
                        waitFrames(1)
                        main { sample() }
                    }
                }
            }
            waitFrames(6)
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
        val elapsed = SystemClock.uptimeMillis() - started
        main {
            val texts = hosted.keys.filterIsInstance<PamTextView>().size
            val rebinds = hosted.values.sumOf { it.size }
            val totals = synchronized(frames) { frames.map { it[0] / 1e6 }.sorted() }
            val work = synchronized(frames) { frames.sumOf { (it[1] + it[2]) / 1e6 } }
            fun pct(p: Double) = totals[((totals.size - 1) * p).toInt()]
            Log.i(
                BENCH_TAG,
                "rows=$FLING_ROWS textNodes=${textRows.size} textViews=$texts viewNodePairs=$rebinds " +
                    "elapsedMs=$elapsed frames=${totals.size} p50=%.2f p90=%.2f p99=%.2f max=%.2f animLayoutMs=%.1f"
                        .format(pct(0.5), pct(0.9), pct(0.99), totals.last(), work),
            )
            assertTrue("rows were bound while flinging", rebinds > textRows.size / 2)
            assertTrue("a long fling reuses text views ($texts views for $rebinds texts shown)", texts * 3 < rebinds)
            // Tail-truncated texts show a copy: compare the full content.
            forEachMountedText { i, view -> assertEquals(chatTexts(i)[textIndex(view)], view.sourceContent().toString()) }
        }
    }

    private fun mount(rows: Int, extent: Float, build: (Int, Float, MutableList<Mutation>) -> Unit) {
        main {
            val mutations = mutableListOf<Mutation>(
                create(1, 0, 0, NodeKind.SCREEN),
                create(2, 1, 0, NodeKind.VIRTUAL_LIST),
                Mutation.Layout(1, Frame(0f, 0f, 360f, 480f)),
                Mutation.Layout(2, Frame(0f, 0f, 360f, 480f)),
            )
            repeat(rows) { i -> build(i, i * extent, mutations) }
            renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
            list = renderer.viewForNode(2) as PamRecyclerList
        }
        waitFrames(6)
    }

    /** A pressable row with one text. */
    private fun row(
        i: Int,
        y: Float,
        out: MutableList<Mutation>,
        text: Map<PropKey, PropValue>,
        cell: Map<PropKey, PropValue> = mapOf(PropKey.ON_PRESS to PropValue.Flag(true)),
    ) {
        out += create(CELL + i, 2, i, NodeKind.PRESSABLE, cell)
        out += create(TEXT + i, CELL + i, 0, NodeKind.TEXT, text)
        out += Mutation.Layout(CELL + i, Frame(0f, y, 360f, 64f))
        out += Mutation.Layout(TEXT + i, Frame(8f, y + 8f, 280f, 40f))
        textRows[TEXT + i] = i
    }

    private fun chatTexts(i: Int) = listOf("Contact $i", "Message number $i with a longer preview line", "12:${i % 60}", "${i % 9 + 1}")

    /** Text index inside a chat row (name, message, time, badge), from its node id. */
    private fun textIndex(view: View): Int {
        val id = textRows.keys.first { renderer.viewForNode(it) === view }
        return ((id - CHAT) % 10 - 3).toInt()
    }

    /** Pressable > avatar, name, message, time, badge > count: eight views. */
    private fun chatRow(i: Int, y: Float, out: MutableList<Mutation>) {
        val base = CHAT + i * 10L
        val texts = chatTexts(i)
        val text = { size: Double, extra: Map<PropKey, PropValue> ->
            mapOf(
                PropKey.FONT_SIZE to PropValue.Decimal(size),
                PropKey.TEXT_COLOR to PropValue.Integer(0xFF202020),
                PropKey.NUMBER_OF_LINES to PropValue.Integer(1),
            ) + extra
        }
        out += create(base, 2, i, NodeKind.PRESSABLE, mapOf(
            PropKey.ON_PRESS to PropValue.Flag(true),
            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
        ))
        out += create(base + 1, base, 0, NodeKind.VIEW, mapOf(
            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF8899AA),
            PropKey.BORDER_RADIUS to PropValue.Decimal(24.0),
        ))
        out += create(base + 3, base, 1, NodeKind.TEXT, text(15.0, mapOf(
            PropKey.TEXT to PropValue.Text(texts[0]),
            PropKey.FONT_WEIGHT to PropValue.Integer(700),
        )))
        out += create(base + 4, base, 2, NodeKind.TEXT, text(14.0, mapOf(PropKey.TEXT to PropValue.Text(texts[1]))))
        out += create(base + 5, base, 3, NodeKind.TEXT, text(12.0, mapOf(PropKey.TEXT to PropValue.Text(texts[2]))))
        out += create(base + 2, base, 4, NodeKind.VIEW, mapOf(
            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF25D366),
            PropKey.BORDER_RADIUS to PropValue.Decimal(10.0),
        ))
        out += create(base + 6, base + 2, 0, NodeKind.TEXT, text(11.0, mapOf(
            PropKey.TEXT to PropValue.Text(texts[3]),
            PropKey.TEXT_COLOR to PropValue.Integer(0xFFFFFFFF),
        )))
        out += Mutation.Layout(base, Frame(0f, y, 360f, 72f))
        out += Mutation.Layout(base + 1, Frame(12f, y + 12f, 48f, 48f))
        out += Mutation.Layout(base + 3, Frame(72f, y + 12f, 200f, 20f))
        out += Mutation.Layout(base + 4, Frame(72f, y + 38f, 240f, 20f))
        out += Mutation.Layout(base + 5, Frame(300f, y + 12f, 48f, 16f))
        out += Mutation.Layout(base + 2, Frame(320f, y + 38f, 20f, 20f))
        out += Mutation.Layout(base + 6, Frame(320f + 6f, y + 40f, 8f, 16f))
        for (k in 3..6) textRows[base + k] = i
    }

    private fun scrollThrough() {
        for (direction in intArrayOf(1, -1)) {
            var steps = 0
            while (read { list.canScrollVertically(direction) } && steps++ < 400) {
                main {
                    list.scrollBy(0, direction * SCROLL_STEP)
                    sample()
                }
                waitFrames(1)
                main { sample() }
            }
        }
        waitFrames(6)
        main { sample() }
    }

    private fun sample() {
        for (id in textRows.keys) {
            renderer.viewForNode(id)?.let { hosted.getOrPut(it) { HashSet() } += id }
        }
    }

    private fun forEachMountedText(action: (Int, PamTextView) -> Unit) {
        for ((id, row) in textRows) {
            val view = renderer.viewForNode(id) as? PamTextView ?: continue
            action(row, view)
        }
    }

    private fun onScreen(view: View): Boolean = view.isShown && view.getGlobalVisibleRect(android.graphics.Rect())

    /** Taps the text's first characters; false when it is not laid out yet. */
    private fun tapFirstCharacter(view: PamTextView): Boolean {
        val layout = view.layout ?: return false
        if (!view.isLaidOut) return false
        val x = view.totalPaddingLeft + layout.getPrimaryHorizontal(1)
        val y = view.totalPaddingTop + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            MotionEvent.obtain(now, now, action, x, y, 0).also {
                view.dispatchTouchEvent(it)
                it.recycle()
            }
        }
        return true
    }

    private fun clockLabel(i: Int): String =
        PamClockSpec(PamTimeSource(null, i * 1_000L, false), null, null, PamClockFormat.PADDED_MINUTES_SECONDS).label(0L)

    private fun commit(vararg mutations: Mutation) {
        main { renderer.commit(listOf(mutations.toList())) }
        instrumentation.waitForIdleSync()
    }

    private fun create(id: Long, parent: Long, index: Int, kind: NodeKind, props: Map<PropKey, PropValue> = emptyMap()) =
        Mutation.Create(NodeSpec(id, parent, index, kind, props))

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun <T> read(block: () -> T): T {
        var value: T? = null
        main { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(message: String, timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (read(condition)) return
            waitFrames(1)
        }
        assertTrue(message, read(condition))
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            main { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            assertTrue("frame callback", latch.await(2, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val ROWS = 200
        const val FLING_ROWS = 300
        const val FLING_VELOCITY = 9_000
        const val SCROLL_STEP = 240
        const val CELL = 100L
        const val TEXT = 1_000L
        const val CHAT = 10_000L
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val BENCH_TAG = "PamCellPoolBench"
    }
}
