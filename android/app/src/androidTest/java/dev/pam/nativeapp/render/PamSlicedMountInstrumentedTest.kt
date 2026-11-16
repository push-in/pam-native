package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sliced mount: a commit that builds a long ScrollView (60 rows of
 * Pressable + Image + Text) builds what the viewport shows inside the commit
 * and the rest over the next frames, with the same final views, geometry and
 * order as building everything at once, and no frame above the budget.
 */
@RunWith(AndroidJUnit4::class)
class PamSlicedMountInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var image: File
    private var widthDp = 0f
    private var heightDp = 0f

    @Before
    fun launch() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        image = File(activity.cacheDir, "sliced-mount-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        awaitFrames(instrumentation, 2)
        instrumentation.runOnMainSync {
            val density = activity.resources.displayMetrics.density
            widthDp = activity.host.width / density
            heightDp = activity.host.height / density
        }
        assertTrue("the host must be laid out", widthDp > 0f && heightDp > ROW_HEIGHT * 4)
    }

    @After
    fun finish() {
        activity.finish()
        image.delete()
    }

    @Test
    fun longScrollMountsItsViewportFirstAndEndsIdenticalToAFullMount() {
        val full = mount(sliced = false).let { renderer ->
            val geometry = onMain { snapshot(renderer) }
            onMain { renderer.close() }
            geometry
        }
        val renderer = renderer()
        val visibleRows = visibleRows(0f)
        commitInFrame {
            renderer.commit(listOf(tree()))
            // Inside the commit's frame, before its traversal: every row the
            // viewport shows is built, the far rows wait.
            visibleRows.forEach { row ->
                assertNotNull("visible row $row is built in the first frame", renderer.viewForNode(rowId(row)))
                assertNotNull(renderer.viewForNode(rowId(row) + TEXT))
                assertNotNull(renderer.viewForNode(rowId(row) + IMAGE))
            }
            assertNull("a row far below the fold waits", renderer.viewForNode(rowId(ROWS - 1)))
            assertTrue(renderer.pendingMountCount() > 0)
        }
        awaitMounted(renderer)
        try {
            assertEquals("same views, geometry, texts and order", full, onMain { snapshot(renderer) })
        } finally {
            onMain { renderer.close() }
        }
    }

    @Test
    fun mountFramesStayWithinBudget() {
        // Warm-up: class loading, JIT and the first-instance costs of each view kind, both paths.
        mount(sliced = false).also { warm -> onMain { warm.close() } }
        mount(sliced = true).also { warm -> onMain { warm.close() } }
        // Best of three runs: the emulator's frame times are noisy.
        val full = (1..RUNS).map { measureMount(sliced = false) }.minBy { it.worst }
        val sliced = (1..RUNS).map { measureMount(sliced = true) }.minBy { it.worst }
        Log.i(TAG, "worst UI frame: full mount ${full.worst}ms, sliced mount ${sliced.worst}ms (commit frame ${sliced.first}ms, slices ${sliced.rest}ms)")
        // The budgets hold on the reference emulator; a slower or loaded host
        // (shared CI runners) stretches every frame alike, so they scale with
        // the full mount measured on the same host, never below the reference.
        val slowdown = maxOf(1.0, full.worst.toDouble() / REFERENCE_FULL_MOUNT_MS)
        val commitBudget = (COMMIT_FRAME_BUDGET_MS * slowdown).toLong()
        val frameBudget = (FRAME_BUDGET_MS * slowdown).toLong()
        assertTrue(
            "the sliced commit frame ${sliced.first}ms exceeds ${commitBudget}ms (full mount ${full.worst}ms)",
            sliced.first <= commitBudget,
        )
        assertTrue("a mount slice frame took ${sliced.rest}ms (budget ${frameBudget}ms, full mount ${full.worst}ms)", sliced.rest <= frameBudget)
        assertTrue(
            "the sliced mount's worst frame ${sliced.worst}ms is not under half the full mount's ${full.worst}ms",
            sliced.worst * 2 <= full.worst,
        )
    }

    /** Worst main-thread frame times (ms): the commit's frame, the frames after it, overall. */
    private class MountFrames(val first: Long, val rest: Long) {
        val worst = maxOf(first, rest)
    }

    @Test
    fun laterCommitsAndScrollingNeverShowAPendingRowMissing() {
        val renderer = renderer()
        val failures = Collections.synchronizedList(ArrayList<String>())
        lateinit var scroll: ViewGroup
        val drawCheck = ViewTreeObserver.OnDrawListener {
            val offset = scroll.scrollY / activity.resources.displayMetrics.density
            visibleRows(offset).filter { it != REMOVED_ROW && it != MOVED_ROW }.forEach { row ->
                if (renderer.viewForNode(rowId(row)) == null) failures += "row $row drawn missing at offset $offset"
            }
        }
        commitInFrame {
            renderer.commit(listOf(tree()))
            scroll = (renderer.viewForNode(SCROLL) as PamScrollContainer).getChildAt(0) as ViewGroup
            activity.host.viewTreeObserver.addOnDrawListener(drawCheck)
            assertNull(renderer.viewForNode(rowId(UPDATED_ROW)))
            // A later commit reaching into pending rows before any slice ran.
            renderer.commit(
                listOf(
                    listOf(
                        Mutation.Update(rowId(UPDATED_ROW) + TEXT, PropKey.TEXT, PropValue.Text("Updated while pending")),
                        Mutation.Remove(rowId(REMOVED_ROW) + TEXT),
                        Mutation.Remove(rowId(REMOVED_ROW) + IMAGE),
                        Mutation.Remove(rowId(REMOVED_ROW)),
                        Mutation.Create(NodeSpec(rowId(GROWN_ROW) + EXTRA, rowId(GROWN_ROW), 2, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Added while pending")))),
                        Mutation.Layout(rowId(GROWN_ROW) + EXTRA, Frame(72f, GROWN_ROW * ROW_HEIGHT + 40f, 120f, 20f)),
                        // A pending row moved into the viewport is built by this commit.
                        Mutation.Layout(rowId(MOVED_ROW), Frame(0f, 0f, widthDp, ROW_HEIGHT)),
                        Mutation.Layout(rowId(MOVED_ROW) + IMAGE, Frame(12f, 12f, 48f, 48f)),
                        Mutation.Layout(rowId(MOVED_ROW) + TEXT, Frame(72f, 12f, widthDp - 84f, 48f)),
                    ),
                ),
            )
            assertNotNull("a pending row laid out into view is built at once", renderer.viewForNode(rowId(MOVED_ROW) + TEXT))
        }
        try {
            // The user scrolls to rows that may still be pending, after this
            // frame's slice ran (the pre-draw check builds them), then jumps
            // to the end.
            commitInFrame {
                scroll.scrollTo(0, ((SCROLLED_TO_ROW * ROW_HEIGHT) * activity.resources.displayMetrics.density).toInt())
            }
            awaitFrames(instrumentation, 2)
            commitInFrame { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            awaitMounted(renderer)
            awaitFrames(instrumentation, 2)
            assertEquals(emptyList<String>(), failures.toList())
            onMain {
                assertEquals("Updated while pending", (renderer.viewForNode(rowId(UPDATED_ROW) + TEXT) as TextView).text.toString())
                assertNull(renderer.viewForNode(rowId(REMOVED_ROW)))
                assertEquals("Added while pending", (renderer.viewForNode(rowId(GROWN_ROW) + EXTRA) as TextView).text.toString())
                val moved = requireNotNull(renderer.viewForNode(rowId(MOVED_ROW)))
                assertEquals(0, moved.top)
                for (row in 0 until ROWS) {
                    if (row == REMOVED_ROW) continue
                    val view = requireNotNull(renderer.viewForNode(rowId(row))) { "row $row" }
                    assertTrue("row $row is attached", view.isAttachedToWindow)
                }
            }
        } finally {
            onMain {
                activity.host.viewTreeObserver.removeOnDrawListener(drawCheck)
                renderer.close()
            }
        }
    }

    @Test
    fun smallCommitsAndHorizontalScrollsBuildEverythingAtOnce() {
        val renderer = renderer()
        try {
            onMain {
                renderer.commit(listOf(tree(rows = 6)))
                assertEquals(0, renderer.pendingMountCount())
                assertNotNull(renderer.viewForNode(rowId(5)))
            }
            onMain { renderer.resetTree() }
            onMain {
                renderer.commit(listOf(tree(horizontal = true)))
                assertEquals("a horizontal scroll is not sliced", 0, renderer.pendingMountCount())
                assertNotNull(renderer.viewForNode(rowId(ROWS - 1)))
            }
        } finally {
            onMain { renderer.close() }
        }
    }

    /** Main-thread frame times from the mount's frame until every view is built. */
    private fun measureMount(sliced: Boolean): MountFrames {
        val frames = Collections.synchronizedList(ArrayList<Long>())
        val metricsThread = HandlerThread("pam-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            frames += metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                metrics.getMetric(FrameMetrics.DRAW_DURATION) +
                metrics.getMetric(FrameMetrics.SYNC_DURATION)
        }
        val renderer = renderer(sliced)
        try {
            awaitFrames(instrumentation, 2)
            onMain { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
            commitInFrame { renderer.commit(listOf(tree())) }
            awaitMounted(renderer)
            awaitFrames(instrumentation, 3)
            // Metrics arrive asynchronously after each frame is presented.
            SystemClock.sleep(250)
        } finally {
            onMain {
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                renderer.close()
            }
            metricsThread.quitSafely()
        }
        val millis = synchronized(frames) { frames.map { it / 1_000_000L } }
        assertTrue("frame metrics were reported", millis.isNotEmpty())
        Log.i(TAG, "sliced=$sliced frames(ms)=$millis")
        return MountFrames(first = millis.first(), rest = millis.drop(1).maxOrNull() ?: 0L)
    }

    private fun mount(sliced: Boolean): PamRenderer {
        val renderer = renderer(sliced)
        onMain { renderer.commit(listOf(tree())) }
        awaitMounted(renderer)
        return renderer
    }

    private fun renderer(sliced: Boolean = true): PamRenderer = onMain {
        PamRenderer(activity, activity.host) { _, _, _ -> }.also { it.slicedMountEnabled = sliced }
    }

    private fun awaitMounted(renderer: PamRenderer) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (onMain { renderer.pendingMountCount() } > 0) {
            check(SystemClock.uptimeMillis() < deadline) { "the sliced mount did not finish" }
            awaitFrames(instrumentation)
        }
        awaitFrames(instrumentation, 2)
    }

    /** Every built row: its views' geometry, text and place among the scroll content's children. */
    private fun snapshot(renderer: PamRenderer): List<String> = (0 until ROWS).map { row ->
        val pressable = renderer.viewForNode(rowId(row)) ?: return@map "row $row missing"
        val text = renderer.viewForNode(rowId(row) + TEXT) as TextView
        val picture = requireNotNull(renderer.viewForNode(rowId(row) + IMAGE))
        val parent = pressable.parent as ViewGroup
        listOf(
            "row $row @${parent.indexOfChild(pressable)}",
            geometry(pressable),
            geometry(picture),
            geometry(text),
            text.text.toString(),
            text.lineCount.toString(),
        ).joinToString(" ")
    }

    private fun geometry(view: View) = "${view.left},${view.top},${view.width}x${view.height}"

    private fun visibleRows(offsetDp: Float): List<Int> = (0 until ROWS).filter { row ->
        val top = row * ROW_HEIGHT - offsetDp
        top < heightDp && top + ROW_HEIGHT > 0f
    }

    private fun tree(rows: Int = ROWS, horizontal: Boolean = false): List<Mutation> = buildList {
        add(Mutation.Create(NodeSpec(ROOT, 0, 0, NodeKind.SCREEN, emptyMap())))
        add(
            Mutation.Create(
                NodeSpec(
                    SCROLL, ROOT, 0, NodeKind.SCROLL,
                    if (horizontal) mapOf(PropKey.SCROLL_HORIZONTAL to PropValue.Flag(true)) else emptyMap(),
                ),
            ),
        )
        add(Mutation.Create(NodeSpec(CONTENT, SCROLL, 0, NodeKind.COLUMN, emptyMap())))
        add(Mutation.Layout(ROOT, Frame(0f, 0f, widthDp, heightDp)))
        add(Mutation.Layout(SCROLL, Frame(0f, 0f, widthDp, heightDp)))
        add(Mutation.Layout(CONTENT, Frame(0f, 0f, widthDp, rows * ROW_HEIGHT)))
        repeat(rows) { row ->
            val id = rowId(row)
            val top = if (horizontal) 0f else row * ROW_HEIGHT
            val left = if (horizontal) row * widthDp else 0f
            add(
                Mutation.Create(
                    NodeSpec(
                        id, CONTENT, row, NodeKind.PRESSABLE,
                        mapOf(
                            PropKey.ON_PRESS to PropValue.Flag(true),
                            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                            PropKey.BORDER_BOTTOM_WIDTH to PropValue.Decimal(1.0),
                            PropKey.BORDER_COLOR to PropValue.Integer(0xFFDDDDDDL),
                        ),
                    ),
                ),
            )
            add(
                Mutation.Create(
                    NodeSpec(
                        id + IMAGE, id, 0, NodeKind.IMAGE,
                        mapOf(
                            PropKey.SOURCE to PropValue.Text(image.toURI().toString()),
                            PropKey.BORDER_RADIUS to PropValue.Decimal(24.0),
                            PropKey.IMAGE_FADE_DURATION_MS to PropValue.Integer(0),
                        ),
                    ),
                ),
            )
            add(
                Mutation.Create(
                    NodeSpec(
                        id + TEXT, id, 1, NodeKind.TEXT,
                        mapOf(
                            PropKey.TEXT to PropValue.Text("Row $row: a message preview long enough to wrap onto a second line"),
                            PropKey.FONT_SIZE to PropValue.Decimal(16.0),
                            PropKey.LINE_HEIGHT to PropValue.Decimal(22.0),
                            PropKey.NUMBER_OF_LINES to PropValue.Integer(2),
                            PropKey.TEXT_COLOR to PropValue.Integer(0xFF222222L),
                        ),
                    ),
                ),
            )
            add(Mutation.Layout(id, Frame(left, top, widthDp, ROW_HEIGHT)))
            add(Mutation.Layout(id + IMAGE, Frame(left + 12f, top + 12f, 48f, 48f)))
            add(Mutation.Layout(id + TEXT, Frame(left + 72f, top + 12f, widthDp - 84f, 48f)))
        }
        add(Mutation.SetRoot(ROOT))
    }

    private fun rowId(row: Int): Long = FIRST_ROW + row * 10L

    private fun commitInFrame(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                try {
                    block()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    done.countDown()
                }
            }
        }
        check(done.await(5, TimeUnit.SECONDS)) { "the commit frame did not run" }
        failure?.let { throw it }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }

    private companion object {
        const val TAG = "PamSlicedMount"
        const val ROWS = 60
        const val ROW_HEIGHT = 72f
        const val ROOT = 1L
        const val SCROLL = 2L
        const val CONTENT = 3L
        const val FIRST_ROW = 100L
        const val IMAGE = 1L
        const val TEXT = 2L
        const val EXTRA = 3L
        const val UPDATED_ROW = 50
        const val REMOVED_ROW = 55
        const val GROWN_ROW = 52
        const val MOVED_ROW = 40
        const val SCROLLED_TO_ROW = 30

        const val RUNS = 3

        /**
         * Main-thread frame (input, animation, layout, draw, sync) of each
         * mount slice: one 60 Hz frame.
         */
        const val FRAME_BUDGET_MS = 16L

        /**
         * The commit's own frame builds every row the viewport shows (about
         * 13 here; ~17 ms measured, against ~56 ms for the full mount). A
         * debug build on the emulator runs this path about 1.6x slower than
         * a release build on the Galaxy S10 (all rows at once: ~60-70 ms vs
         * ~42 ms), so this is about one 60 Hz frame there.
         */
        const val COMMIT_FRAME_BUDGET_MS = 24L

        /** Worst frame of the full mount on the reference emulator (debug build). */
        const val REFERENCE_FULL_MOUNT_MS = 56.0
    }
}
