package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A list inside a sheet or modal whose height animates (open, snap, keyboard)
 * must keep its offset: only a list opened at its end (`initialScrollIndex`)
 * or scrolled there by a real scroll follows the end when its viewport
 * resizes, and `followEndOnResize=false` turns that off. Lists in sheets used
 * to open scrolled to their last rows.
 */
@RunWith(AndroidJUnit4::class)
class PamListFollowEndOnResizeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aListAtItsStartKeepsOffsetZeroWhileItsSheetGrowsAndShrinks() = withList(rows = 40, height = 120) { list ->
        for (height in listOf(240, 480, 900, 1_400, 700, 300)) {
            resize(list, height)
            main { assertEquals("offset after resizing to $height px", 0, list.computeVerticalScrollOffset()) }
        }
    }

    @Test fun aShortListWhoseRowsAllFitStaysAtItsStart() = withList(rows = 4, height = 200) { list ->
        for (height in listOf(400, 900, 250)) {
            resize(list, height)
            main { assertEquals("offset after resizing to $height px", 0, list.computeVerticalScrollOffset()) }
        }
    }

    @Test fun aListOpenedAtItsEndKeepsItsEndVisibleWhenItsViewportShrinks() =
        withList(rows = 40, height = 900, initialIndex = 39) { list ->
            awaitTrue("opened at the end") { !list.canScrollVertically(1) }
            resize(list, 600)
            awaitTrue("still at the end after shrinking") { !list.canScrollVertically(1) }
        }

    @Test fun aListScrolledToItsEndFollowsIt() = withList(rows = 40, height = 900) { list ->
        // Row extents are dp: scroll by more than the content's pixel height.
        main { list.scrollBy(0, list.computeVerticalScrollRange()) }
        awaitTrue("scrolled to the end") { !list.canScrollVertically(1) }
        resize(list, 600)
        awaitTrue("still at the end after shrinking") { !list.canScrollVertically(1) }
    }

    @Test fun followEndOnResizeFalseKeepsTheOffset() =
        withList(rows = 40, height = 900, initialIndex = 39, followEnd = false) { list ->
            awaitTrue("opened at the end") { !list.canScrollVertically(1) }
            var before = 0
            main { before = list.computeVerticalScrollOffset() }
            resize(list, 600)
            main {
                assertEquals("offset kept", before, list.computeVerticalScrollOffset())
                assertTrue("the last rows are below the shrunk viewport", list.canScrollVertically(1))
            }
        }

    @Test fun anInvertedListIsNeverMovedByAResize() = withList(rows = 40, height = 900, inverted = true) { list ->
        var before = 0
        main { before = list.computeVerticalScrollOffset() }
        resize(list, 600)
        main { assertEquals(before, list.computeVerticalScrollOffset()) }
    }

    private fun resize(list: PamRecyclerList, height: Int) {
        main {
            list.layoutParams = list.layoutParams.apply { this.height = height }
        }
        instrumentation.waitForIdleSync()
        // onSizeChanged posts the follow-end scroll.
        SystemClock.sleep(48)
        instrumentation.waitForIdleSync()
    }

    private fun withList(
        rows: Int,
        height: Int,
        initialIndex: Int = 0,
        followEnd: Boolean = true,
        inverted: Boolean = false,
        block: (PamRecyclerList) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            lateinit var list: PamRecyclerList
            main {
                list = PamRecyclerList(activity)
                list.setFollowEndOnResize(followEnd)
                list.setInverted(inverted)
                if (initialIndex > 0) list.setInitialIndex(initialIndex)
                activity.host.addView(list, FrameLayout.LayoutParams(600, height))
                list.setRowHeight(80f)
                val ids = (1L..rows.toLong()).toList()
                list.setRichItems(
                    ids = ids,
                    extents = ids.associateWith { 80f },
                    mount = { _, holder ->
                        holder.addView(
                            View(activity),
                            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                        )
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

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(16)
        }
        throw AssertionError("Timed out waiting until $description")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
