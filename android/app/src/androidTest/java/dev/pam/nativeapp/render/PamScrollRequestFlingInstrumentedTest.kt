package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A scroll request issued while a fling is still running lands exactly on
 * its target. Before 1.41.0 the fling kept moving after the jump, and offset 0
 * resolved to 1 px: a
 * "scroll to top" stopped ~40 px short on the S10.
 */
@RunWith(AndroidJUnit4::class)
class PamScrollRequestFlingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun virtualListJumpToTopStopsTheFlingInProgress() = withList { list ->
        main {
            list.scrollToLogicalOffset(1_200f)
            list.fling(0, 9_000)
        }
        SystemClock.sleep(48)
        main {
            assertTrue("The list must be flinging when the request arrives", list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING)
            list.scrollToLogicalOffset(0f)
        }
        SystemClock.sleep(700)
        main {
            assertEquals("No fling may continue after the jump", 0, list.computeVerticalScrollOffset())
            assertEquals(androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE, list.scrollState)
        }
    }

    @Test fun virtualListJumpToZeroLandsOnThePixel() = withList { list ->
        main { list.scrollToLogicalOffset(1_200f) }
        SystemClock.sleep(200)
        main { list.scrollToLogicalOffset(0f) }
        SystemClock.sleep(200)
        main {
            assertEquals("Offset 0 is the very top (not 1 px below it)", 0, list.computeVerticalScrollOffset())
            assertTrue(!list.canScrollVertically(-1))
        }
    }

    @Test fun virtualListAnimatedRequestGlidesAndSnapsExactly() = withList { list ->
        main {
            list.scrollToLogicalOffset(1_500f)
            list.fling(0, 9_000)
        }
        SystemClock.sleep(48)
        main { list.scrollToLogicalOffset(0f, animated = true) }
        SystemClock.sleep(80)
        main {
            val offset = list.computeVerticalScrollOffset()
            assertTrue("Animated request glides instead of jumping (offset $offset)", offset > 0)
        }
        awaitIdle { list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE }
        main { assertEquals(0, list.computeVerticalScrollOffset()) }
    }

    @Test fun scrollViewJumpToTopStopsTheFlingInProgress() = withScroll { scroll ->
        main {
            (scroll.getChildAt(0) as ScrollView).scrollTo(0, 1_500)
            (scroll.getChildAt(0) as ScrollView).fling(9_000)
        }
        SystemClock.sleep(48)
        main {
            scroll.setScrollTargetOffset(0f)
            scroll.requestScroll()
        }
        SystemClock.sleep(700)
        main { assertEquals("No fling may continue after the jump", 0, scroll.snapshotOffsetPixels().second) }
    }

    @Test fun scrollViewAnimatedRequestGlidesToTheTarget() = withScroll { scroll ->
        main {
            (scroll.getChildAt(0) as ScrollView).scrollTo(0, 1_500)
            (scroll.getChildAt(0) as ScrollView).fling(9_000)
        }
        SystemClock.sleep(48)
        main {
            scroll.setScrollAnimated(true)
            scroll.setScrollTargetOffset(0f)
            scroll.requestScroll()
        }
        SystemClock.sleep(120)
        main { assertTrue("Animated request glides", scroll.snapshotOffsetPixels().second > 0) }
        SystemClock.sleep(900)
        main { assertEquals(0, scroll.snapshotOffsetPixels().second) }
    }

    private fun withList(block: (PamRecyclerList) -> Unit) {
        val activity = launch()
        try {
            lateinit var list: PamRecyclerList
            main {
                list = PamRecyclerList(activity)
                activity.host.addView(list, FrameLayout.LayoutParams(600, 900))
                list.setRowHeight(80f)
                val ids = (1L..200L).toList()
                list.setRichItems(
                    ids = ids,
                    extents = ids.associateWith { 80f },
                    mount = { _, holder ->
                        holder.addView(View(activity), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    },
                    unmount = { _, holder -> holder.removeAllViews() },
                )
            }
            instrumentation.waitForIdleSync()
            block(list)
        } finally {
            main { activity.host.removeAllViews(); activity.finish() }
        }
    }

    private fun withScroll(block: (PamScrollContainer) -> Unit) {
        val activity = launch()
        try {
            lateinit var scroll: PamScrollContainer
            main {
                scroll = PamScrollContainer(activity)
                scroll.insert(View(activity).apply { layoutParams = FrameLayout.LayoutParams(600, 40_000) })
                activity.host.addView(scroll, FrameLayout.LayoutParams(600, 900))
            }
            instrumentation.waitForIdleSync()
            block(scroll)
        } finally {
            main { activity.host.removeAllViews(); activity.finish() }
        }
    }

    private fun launch(): PamTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as PamTestActivity

    private fun awaitIdle(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(30)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
