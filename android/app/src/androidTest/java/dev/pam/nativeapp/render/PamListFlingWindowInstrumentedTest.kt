package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A windowed list asks PHP once per released fling for every row the fling
 * sweeps and the screen it lands on (the platform spline), and rows that a
 * commit changes while they are bound off screen build their views within
 * the per-frame mount budget, always before they are drawn.
 */
@RunWith(AndroidJUnit4::class)
class PamListFlingWindowInstrumentedTest {
    @Test
    fun aReleasedFlingProjectsTheRowsItSweepsAndLandsOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var list: PamRecyclerList
        try {
            onMain(instrumentation) {
                list = PamRecyclerList(activity)
                list.rowSpan = { id -> intArrayOf(id.toInt() - 1, 1) }
                activity.host.addView(
                    list,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                val ids = (1L..400L).toList()
                list.setRichItems(ids, ids.associateWith { 120f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { assertEquals("no path at rest", null, list.flingPathRows()) }
            fling(instrumentation, list)
            onMain(instrumentation) {
                assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                val visible = requireNotNull(list.visibleRows())
                val path = list.flingPathRows()
                assertNotNull("a released fling projects its path", path)
                path!!
                assertEquals("the path starts at the rows on screen", visible[0], path[0])
                assertTrue("the path reaches past the screen it starts on (${path.toList()})", path[1] > visible[1] + 2)
                assertTrue("adapter positions cover the path", path[3] >= path[1] && path[2] <= visible[0])
            }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var landedLast = -1
            var projected = -1
            onMain(instrumentation) { projected = list.flingPathRows()?.get(1) ?: -1 }
            while (SystemClock.uptimeMillis() < deadline) {
                var idle = false
                onMain(instrumentation) { idle = list.scrollState == RecyclerView.SCROLL_STATE_IDLE }
                if (idle) break
                SystemClock.sleep(30)
            }
            onMain(instrumentation) {
                landedLast = requireNotNull(list.visibleRows())[1]
                assertEquals("no path once the fling rests", null, list.flingPathRows())
            }
            assertTrue("the projection lands near where the fling rests ($projected vs $landedLast)", kotlin.math.abs(projected - landedLast) <= 3)
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun aCellChangedOffScreenBuildsWithinTheBudgetBeforeItIsShown() {
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
                list.setItemViewCacheSize(4)
                val ids = (1L..60L).toList()
                list.setRichItems(ids, ids.associateWith { 120f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            instrumentation.waitForIdleSync()
            var repairedOnScreen = 0
            var deferredId = -1L
            var repairedOffScreen = false
            onMain(instrumentation) {
                val manager = list.layoutManager as LinearLayoutManager
                val first = manager.findFirstVisibleItemPosition()
                val last = manager.findLastVisibleItemPosition()
                // A row on screen is repaired by the caller, now.
                assertFalse(list.deferCellRepair((first + 1).toLong()) { repairedOnScreen++ })
                // A bound row off screen (laid out ahead) waits for the budget.
                for (position in last + 2 until last + 6) {
                    val id = (position + 1).toLong()
                    if (manager.findViewByPosition(position) != null && list.deferCellRepair(id) { repairedOffScreen = true }) {
                        deferredId = id
                        assertFalse("deferred, not run inside the commit", repairedOffScreen)
                        break
                    }
                }
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                assertEquals(0, repairedOnScreen)
                // A row bound ahead (when the layout bound one) is repaired by the next frames' budget.
                if (deferredId >= 0) assertTrue("the drain repairs it", repairedOffScreen)
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun aPageLandingMidFlingNeverJumpsTheRowsOnScreen() {
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
                list.setRichItems(ids, ids.associateWith { 72f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            instrumentation.waitForIdleSync()
            fling(instrumentation, list)
            var anchorId = -1L
            val tops = ArrayList<Int>()
            fun sample() = onMain(instrumentation) {
                val manager = list.layoutManager as LinearLayoutManager
                if (anchorId < 0) anchorId = list.richIdAt(manager.findFirstVisibleItemPosition() + 2) ?: -1L
                val position = (0 until (list.adapter?.itemCount ?: 0)).firstOrNull { list.richIdAt(it) == anchorId } ?: return@onMain
                manager.findViewByPosition(position)?.let { tops += it.top }
            }
            repeat(3) { awaitFrames(instrumentation, 1); sample() }
            onMain(instrumentation) {
                assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                // An older page (taller rows) lands above the viewport mid-fling.
                val ids = (-30L..400L).filter { it != 0L }
                list.setRichItems(ids, ids.associateWith { if (it < 0) 140f else 72f }, ::bindRow, { _, holder -> holder.removeAllViews() })
            }
            repeat(4) { awaitFrames(instrumentation, 1); sample() }
            val steps = tops.zipWithNext { a, b -> a - b }
            assertTrue("the row keeps moving with the fling: $tops", steps.all { it >= 0 })
            val before = steps.take(2).maxOrNull() ?: 0
            assertTrue("no step jumps past the fling's own pace: $steps", steps.all { it <= before * 2 + 24 })
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
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
            instrumentation.sendPointerSync(MotionEvent.obtain(down, down + step * 8L, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + steps * 8L, MotionEvent.ACTION_UP, x, endY, 0))
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
