package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native scrolling guarantees of virtual lists, for any app: a programmatic
 * scroll committed during the user's own fling waits until the list rests
 * (the fling is never cut short), and rows inserted or removed above the
 * viewport (prepended pages, sliding windows, rows prepended right below a
 * header) keep the first visible row at its exact pixel offset.
 */
@RunWith(AndroidJUnit4::class)
class PamListNativeScrollInstrumentedTest {
    @Test
    fun scrollRequestDuringUserFlingWaitsUntilRest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var list: PamRecyclerList
        try {
            onMain(instrumentation) {
                list = PamRecyclerList(activity)
                activity.host.addView(
                    list,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                val ids = (1L..400L).toList()
                list.setRichItems(ids, ids.associateWith { 64f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            instrumentation.waitForIdleSync()
            fling(instrumentation, list)
            var deferredRan = false
            onMain(instrumentation) {
                assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                assertTrue(list.deferWhileUserScrolls { deferredRan = true })
                // Rows joining above the viewport mid-fling: no positioning.
                val ids = (-15L..400L).filter { it != 0L }
                list.setRichItems(ids, ids.associateWith { 64f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            awaitFrames(instrumentation, 4)
            onMain(instrumentation) {
                assertEquals("the fling keeps running", RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                assertFalse(deferredRan)
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                var idle = false
                onMain(instrumentation) { idle = list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
                if (idle) break
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue("the request runs once the fling rests", deferredRan)
                var immediate = false
                assertFalse(list.deferWhileUserScrolls { immediate = true })
                assertFalse(immediate)
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }


    @Test
    fun prependAboveTheViewportKeepsTheFirstVisibleRowPixelOffset() {
        anchored(
            initial = (1L..200L).toList(),
            scrollTo = 50 to -20,
            next = { ids -> (-30L..-1L).toList() + ids },
        )
    }

    @Test
    fun rowsPrependedBelowAVisibleHeaderKeepTheFollowingRowInPlace() {
        anchored(
            initial = listOf(HEADER) + (1L..200L).toList(),
            scrollTo = 0 to 0,
            next = { ids -> listOf(HEADER) + (-30L..-1L).toList() + ids.drop(1) },
            anchorAfterHeader = true,
        )
    }

    @Test
    fun aSlidingWindowKeepsTheVisibleRowsInPlace() {
        anchored(
            initial = (1L..80L).toList(),
            scrollTo = 30 to -10,
            next = { ids -> ids.drop(16) + (81L..96L).toList() },
        )
        anchored(
            initial = (17L..96L).toList(),
            scrollTo = 20 to -10,
            next = { ids -> (1L..16L).toList() + ids.dropLast(16) },
        )
    }

    private fun anchored(
        initial: List<Long>,
        scrollTo: Pair<Int, Int>,
        next: (List<Long>) -> List<Long>,
        anchorAfterHeader: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var list: PamRecyclerList
        var anchorId = 0L
        var anchorTop = 0
        try {
            onMain(instrumentation) {
                list = PamRecyclerList(activity)
                activity.host.addView(
                    list,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                list.setRichItems(initial, extents(initial), ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            instrumentation.waitForIdleSync()
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                manager.scrollToPositionWithOffset(scrollTo.first, scrollTo.second)
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                val position = manager.findFirstVisibleItemPosition() + if (anchorAfterHeader) 1 else 0
                anchorId = initial[position]
                anchorTop = requireNotNull(manager.findViewByPosition(position)).top
                val replacement = next(initial)
                list.setRichItems(replacement, extents(replacement), ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            awaitFrames(instrumentation, 4)
            onMain(instrumentation) {
                val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
                val replacement = next(initial)
                val view = requireNotNull(manager.findViewByPosition(replacement.indexOf(anchorId))) {
                    "row $anchorId left the viewport"
                }
                assertEquals("row $anchorId keeps its pixel offset", anchorTop, view.top)
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    /** Rows of different heights: an estimate-based jump would show. */
    private fun extents(ids: List<Long>): Map<Long, Float> =
        ids.associateWith { id -> if (id == HEADER) 40f else 48f + (Math.floorMod(id, 5L) * 17f) }

    private companion object {
        const val HEADER = 1_000_000L
    }

    private fun bindRow(id: Long, holder: FrameLayout) {
        holder.addView(TextView(holder.context).apply { text = "Row $id" })
    }

    private fun fling(instrumentation: Instrumentation, list: PamRecyclerList) {
        val location = IntArray(2)
        onMain(instrumentation) { list.getLocationOnScreen(location) }
        val x = location[0] + list.width / 2f
        val startY = location[1] + list.height * 0.8f
        val endY = location[1] + list.height * 0.2f
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, startY, 0))
        val steps = 6
        for (step in 1..steps) {
            val y = startY + (endY - startY) * step / steps
            instrumentation.sendPointerSync(
                MotionEvent.obtain(down, down + step * 8L, MotionEvent.ACTION_MOVE, x, y, 0),
            )
        }
        instrumentation.sendPointerSync(
            MotionEvent.obtain(down, down + steps * 8L + 4L, MotionEvent.ACTION_UP, x, endY, 0),
        )
    }

    private fun awaitFrames(instrumentation: Instrumentation, frames: Int) {
        repeat(frames) {
            val latch = java.util.concurrent.CountDownLatch(1)
            onMain(instrumentation) { android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }
}
