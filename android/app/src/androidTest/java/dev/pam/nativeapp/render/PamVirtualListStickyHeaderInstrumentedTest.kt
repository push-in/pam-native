package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FlashList parity: the pinned sticky header of a VirtualizedList (and of a
 * VirtualGrid) is a real interactive view. Presses, pressed state and
 * accessibility reach it, the row scrolling underneath never receives its
 * touches, and rows keep virtualizing while it is pinned.
 */
@RunWith(AndroidJUnit4::class)
class PamVirtualListStickyHeaderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    @Test
    fun pinnedHeaderIsInteractiveAndTheRowUnderItNeverReceivesItsTouches() = withList(columns = 1) { session ->
        val (list, renderer, presses) = session
        val button = onMain { renderer.viewForNode(HEADER_BUTTON)!! }
        scrollBy(list, 400f)
        onMain {
            val pinned = requireNotNull(list.pinnedStickyHeader()) { "a header is pinned" }
            assertEquals("pinned to the viewport top", 0, pinned.top)
            assertSame("the same native views move into the pinned header", button, renderer.viewForNode(HEADER_BUTTON))
            assertTrue("the header button lives in the pinned holder", button.isDescendantOf(pinned))
            assertSame("the pinned header draws and hit-tests above the rows", pinned, list.getChildAt(list.childCount - 1))
            assertTrue("rows keep virtualizing", list.childCount < ROWS)
        }
        tap(list, 240f, 24f)
        assertEquals("a press on the pinned header button", listOf(HEADER_BUTTON), presses.toList())
        presses.clear()
        tap(list, 60f, 24f)
        assertEquals("the header background owns the touch; the row under it is not pressed", emptyList<Long>(), presses.toList())
        tap(list, 60f, 48f + 40f)
        assertEquals("rows below the header keep receiving touches", 1, presses.size)
        assertTrue(presses.single() in ROW_BASE until ROW_BASE + ROWS)
        presses.clear()

        // Pressed state follows the finger on the pinned header.
        val downAt = SystemClock.uptimeMillis()
        onMain {
            list.dispatchTouchEvent(event(downAt, MotionEvent.ACTION_DOWN, 240f, 24f))
            assertTrue("pressed state on the pinned header button", button.isPressed || button.hasPressedDescendant())
            list.dispatchTouchEvent(event(downAt, MotionEvent.ACTION_CANCEL, 240f, 24f))
        }
        onMain {
            val info = button.createAccessibilityNodeInfo()
            assertTrue("the pinned header is visible to accessibility", info.isVisibleToUser)
            assertTrue(button.isAttachedToWindow)
        }

        // Back at the top the header returns to its own row, still interactive.
        scrollBy(list, -10_000f)
        onMain {
            assertNull("nothing is pinned at the top", list.pinnedStickyHeader())
            assertSame(button, renderer.viewForNode(HEADER_BUTTON))
            assertTrue(button.isAttachedToWindow)
            val holder = button.holderIn(list)
            assertEquals("the header is back in its row at the top", 0, holder.top)
        }
        tap(list, 240f, 24f)
        assertEquals(listOf(HEADER_BUTTON), presses.toList())
    }

    @Test
    fun theNextHeaderPushesThePinnedOneAwayAndTakesOver() = withList(columns = 1) { session ->
        val (list, renderer, _) = session
        // Second header at 48 + 16 * 56 = 944 dp.
        scrollBy(list, 944f - 20f)
        onMain {
            val pinned = requireNotNull(list.pinnedStickyHeader()) { "a header is pinned" }
            assertTrue("the first header is pinned", renderer.viewForNode(HEADER_BUTTON)!!.isDescendantOf(pinned))
            assertEquals("pushed up by the next header", -dp(28f), pinned.top.toFloat(), 2f)
        }
        scrollBy(list, 60f)
        onMain {
            val pinned = requireNotNull(list.pinnedStickyHeader()) { "a header is pinned" }
            assertEquals(0, pinned.top)
            assertTrue("the second header took over", renderer.viewForNode(SECOND_HEADER_BUTTON)!!.isDescendantOf(pinned))
            assertSame(pinned, renderer.viewForNode(SECOND_HEADER_BUTTON)!!.holderIn(list))
        }
        scrollBy(list, -10_000f)
        onMain {
            assertNull(list.pinnedStickyHeader())
            assertTrue("the first header is mounted again", renderer.viewForNode(HEADER_BUTTON)?.isAttachedToWindow == true)
        }
    }

    @Test
    fun steppingThroughTheListKeepsExactlyOneMountedHeaderAndNoBlankRows() = withList(columns = 1) { session ->
        val (list, renderer, _) = session
        repeat(2) { pass ->
            val step = if (pass == 0) 37f else -37f
            repeat(60) {
                onMain { list.scrollBy(0, dp(step).toInt()) }
                onMain {
                    val pinned = list.pinnedStickyHeader()
                    for (button in listOf(HEADER_BUTTON, SECOND_HEADER_BUTTON)) {
                        val view = renderer.viewForNode(button) ?: continue
                        if (!view.isAttachedToWindow) continue
                        val holder = view.holderIn(list)
                        assertTrue("header views live in exactly one holder", holder.parent === list)
                    }
                    if (pinned != null) {
                        assertTrue("the pinned header has content", (pinned as ViewGroup).childCount > 0)
                        assertSame(pinned, list.getChildAt(list.childCount - 1))
                    }
                    val viewport = android.graphics.Rect(0, 0, list.width, list.height)
                    for (index in 0 until list.childCount) {
                        val holder = list.getChildAt(index) as ViewGroup
                        if (holder === pinned) continue
                        val bounds = android.graphics.Rect(holder.left, holder.top, holder.right, holder.bottom)
                        val covered = pinned != null && holder.bottom <= pinned.bottom
                        if (bounds.intersect(viewport) && !covered) {
                            assertTrue("visible row ${list.getChildAdapterPosition(holder)} is blank", holder.childCount > 0)
                        }
                    }
                }
            }
        }
        // A commit while pinned updates the pinned views in place.
        scrollBy(list, 400f)
        onMain {
            renderer.commit(listOf(listOf(Mutation.Update(HEADER_BUTTON, PropKey.BACKGROUND_COLOR, PropValue.Integer(0xFFFF0000)))))
        }
        waitFrames(3)
        onMain {
            val pinned = requireNotNull(list.pinnedStickyHeader())
            assertTrue(renderer.viewForNode(HEADER_BUTTON)!!.isDescendantOf(pinned))
        }
    }

    @Test
    fun pinnedVirtualGridHeaderIsInteractive() = withList(columns = 2) { session ->
        val (list, renderer, presses) = session
        scrollBy(list, 400f)
        onMain {
            val pinned = requireNotNull(list.pinnedStickyHeader()) { "a header is pinned" }
            assertEquals(0, pinned.top)
            assertTrue(renderer.viewForNode(HEADER_BUTTON)!!.isDescendantOf(pinned))
        }
        tap(list, 240f, 24f)
        assertEquals(listOf(HEADER_BUTTON), presses.toList())
        presses.clear()
        tap(list, 60f, 24f)
        assertEquals(emptyList<Long>(), presses.toList())
    }

    private data class Session(
        val list: PamRecyclerList,
        val renderer: PamRenderer,
        val presses: MutableList<Long>,
    )

    private fun withList(columns: Int, block: (Session) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val presses = mutableListOf<Long>()
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (kind == EventKind.PRESS.value) synchronized(presses) { presses += id }
                }
                renderer.commit(listOf(mutations(columns)))
                list = activity.host.findListView()
            }
            instrumentation.waitForIdleSync()
            waitFrames(4)
            block(Session(list, renderer, presses))
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun mutations(columns: Int): List<Mutation> = buildList {
        add(Mutation.Create(spec(1, 0, 0, NodeKind.SCREEN)))
        add(
            Mutation.Create(
                spec(2, 1, 0, NodeKind.VIRTUAL_LIST, mapOf(PropKey.LIST_NUM_COLUMNS to PropValue.Integer(columns.toLong()))),
            ),
        )
        add(Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)))
        add(Mutation.Layout(2, Frame(0f, 0f, 320f, 480f)))
        var y = 0f
        var index = 0
        fun header(cell: Long, button: Long) {
            add(
                Mutation.Create(
                    spec(
                        cell, 2, index++, NodeKind.VIEW,
                        mapOf(
                            PropKey.STICKY_HEADER to PropValue.Flag(true),
                            PropKey.LIST_FULL_SPAN to PropValue.Flag(true),
                            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00),
                        ),
                    ),
                ),
            )
            add(
                Mutation.Create(
                    spec(
                        button, cell, 0, NodeKind.PRESSABLE,
                        mapOf(
                            PropKey.ON_PRESS to PropValue.Flag(true),
                            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF0000FF),
                        ),
                    ),
                ),
            )
            add(Mutation.Layout(cell, Frame(0f, y, 320f, 48f)))
            add(Mutation.Layout(button, Frame(160f, y, 160f, 48f)))
            y += 48f
        }
        header(HEADER, HEADER_BUTTON)
        for (row in 0 until ROWS) {
            if (row == 16) header(SECOND_HEADER, SECOND_HEADER_BUTTON)
            val id = ROW_BASE + row
            add(
                Mutation.Create(
                    spec(
                        id, 2, index++, NodeKind.PRESSABLE,
                        mapOf(
                            PropKey.ON_PRESS to PropValue.Flag(true),
                            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFEEEEEE),
                        ),
                    ),
                ),
            )
            val width = 320f / columns
            val x = if (columns > 1) (row % columns) * width else 0f
            add(Mutation.Layout(id, Frame(x, y, width, 56f)))
            if (columns == 1 || row % columns == columns - 1) y += 56f
        }
        add(Mutation.SetRoot(1))
    }

    private fun scrollBy(list: PamRecyclerList, dpDelta: Float) {
        onMain { list.scrollBy(0, dp(dpDelta).toInt()) }
        waitFrames(4)
    }

    /** Taps [list] at a point in dp relative to its top-left corner. */
    private fun tap(list: PamRecyclerList, x: Float, y: Float) {
        val downAt = SystemClock.uptimeMillis()
        onMain {
            list.dispatchTouchEvent(event(downAt, MotionEvent.ACTION_DOWN, x, y))
            list.dispatchTouchEvent(event(downAt, MotionEvent.ACTION_UP, x, y, 40))
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(50)
        instrumentation.waitForIdleSync()
    }

    private fun event(downAt: Long, action: Int, x: Float, y: Float, after: Long = 0): MotionEvent =
        MotionEvent.obtain(downAt, downAt + after, action, dp(x), dp(y), 0)

    private fun dp(value: Float): Float = value * density

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }
        SystemClock.sleep(16)
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current = parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    /** The RecyclerView child (row holder or pinned header) containing this view. */
    private fun View.holderIn(list: PamRecyclerList): View {
        var current: View = this
        while (current.parent !== list) current = current.parent as View
        return current
    }

    private fun View.hasPressedDescendant(): Boolean {
        if (isPressed) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { getChildAt(it).hasPressedDescendant() }
    }

    private fun View.findListView(): PamRecyclerList {
        if (this is PamRecyclerList) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                runCatching { return getChildAt(index).findListView() }
            }
        }
        error("No PamRecyclerList mounted")
    }

    private fun spec(
        id: Long,
        parent: Long,
        index: Int,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ) = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private companion object {
        const val ROWS = 30
        const val ROW_BASE = 100L
        const val HEADER = 3L
        const val HEADER_BUTTON = 4L
        const val SECOND_HEADER = 5L
        const val SECOND_HEADER_BUTTON = 6L
    }
}
