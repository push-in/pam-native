package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #290: a list whose rows arrive after it mounted (a cached page read
 * asynchronously, then the API page) and are re-measured at rest (day headers
 * shorter than the row estimate) must open at offset 0, like a React Native
 * FlatList, instead of ~70 dp down with the first header hidden.
 */
@RunWith(AndroidJUnit4::class)
class PamListOpensAtStartInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rowsArrivingAfterMountAndReMeasuredKeepOffsetZero() = withEmptyList { list ->
        submit(list, page(CACHED), estimated = true)
        assertAtStart(list, "cached page")
        submit(list, page(CACHED), estimated = false)
        assertAtStart(list, "cached page re-measured")
    }

    @Test fun rowsArrivingAndReMeasuredInOneFrameKeepOffsetZero() = withEmptyList { list ->
        main {
            set(list, page(CACHED), estimated = true)
            set(list, page(CACHED), estimated = false)
        }
        settle()
        assertAtStart(list, "cached page re-measured before its first layout")
    }

    @Test fun theApiPageReplacingTheCachedOneKeepsOffsetZero() = withEmptyList { list ->
        submit(list, page(CACHED), estimated = true)
        submit(list, page(CACHED), estimated = false)
        // Newer rows (a new day) land above the cached ones, estimated first.
        val fresh = page(FRESH) + page(CACHED)
        submit(list, fresh, estimated = true, measured = page(CACHED).toSet())
        assertAtStart(list, "API page")
        submit(list, fresh, estimated = false)
        assertAtStart(list, "API page re-measured")
    }

    @Test fun theApiPageLandingInTheSameFrameAsTheCacheKeepsOffsetZero() = withEmptyList { list ->
        val fresh = page(FRESH) + page(CACHED)
        main {
            set(list, page(CACHED), estimated = true)
            set(list, page(CACHED), estimated = false)
            set(list, fresh, estimated = true, measured = page(CACHED).toSet())
        }
        settle()
        main { set(list, fresh, estimated = false) }
        settle()
        assertAtStart(list, "API page re-measured")
    }

    @Test fun aRowLandingUnderTheFirstHeaderKeepsOffsetZero() = withEmptyList { list ->
        // The statement screen: a leading spacer row, then today's header and
        // its rows; the API page adds a newer row right under the header.
        val cached = listOf(LEADING) + page(CACHED)
        submit(list, cached, estimated = true)
        submit(list, cached, estimated = false)
        val fresh = listOf(LEADING, CACHED, FRESH + 1) + page(CACHED).drop(1)
        submit(list, fresh, estimated = true, measured = cached.toSet())
        assertAtStart(list, "API row under the first header")
        submit(list, fresh, estimated = false)
        assertAtStart(list, "API row re-measured")
    }

    @Test fun aRowLandingUnderTheFirstHeaderAfterTheReaderScrolledBackKeepsTheReadersRow() = withEmptyList { list ->
        // A reader who scrolled (a chat's "loading older" row at the top)
        // keeps the row they were reading when rows land above it.
        val cached = listOf(LEADING) + page(CACHED)
        submit(list, cached, estimated = false)
        main { list.scrollBy(0, dp(list, 300f)) }
        settle()
        main { list.scrollBy(0, -dp(list, 300f)) }
        settle()
        var before = ""
        main { before = rowTop(list, CACHED + 1) }
        submit(list, listOf(LEADING, CACHED, FRESH + 1) + page(CACHED).drop(1), estimated = false)
        main { assertEquals("reader's row kept", before, rowTop(list, CACHED + 1)) }
    }

    @Test fun aListTheReaderScrolledKeepsItsFirstFullyVisibleRowWhenRowsReMeasure() = withEmptyList { list ->
        submit(list, page(CACHED), estimated = true)
        main { list.scrollBy(0, dp(list, 200f)) }
        settle()
        var before = ""
        main { before = firstFullyVisible(list) }
        submit(list, page(CACHED), estimated = false)
        main { assertEquals("first fully visible row kept", before, firstFullyVisible(list)) }
    }

    /** Row ids of a page: a day header (id % 10 == 0) and four rows, repeated. */
    private fun page(start: Long): List<Long> = (0 until 6).flatMap { day ->
        (0..4).map { start + day * 10L + it }
    }

    private fun extent(id: Long, estimated: Boolean): Float = when {
        id == LEADING -> 8f
        estimated -> ESTIMATE
        id % 10 == 0L -> HEADER
        else -> ROW
    }

    private fun set(list: PamRecyclerList, ids: List<Long>, estimated: Boolean, measured: Set<Long> = emptySet()) {
        list.setRichItems(
            ids = ids,
            extents = ids.associateWith { extent(it, estimated && it !in measured) },
            mount = { _, holder ->
                holder.addView(
                    View(list.context),
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            },
            unmount = { _, holder -> holder.removeAllViews() },
        )
    }

    private fun submit(list: PamRecyclerList, ids: List<Long>, estimated: Boolean, measured: Set<Long> = emptySet()) {
        main { set(list, ids, estimated, measured) }
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(48)
        instrumentation.waitForIdleSync()
    }

    private fun assertAtStart(list: PamRecyclerList, step: String) = main {
        assertEquals("offset after $step (first visible ${firstFullyVisible(list)})", 0, list.computeVerticalScrollOffset())
        assertEquals("top row after $step", 0, list.getChildAt(0)?.top ?: 0)
    }

    private fun firstFullyVisible(list: PamRecyclerList): String {
        val child = (0 until list.childCount).map(list::getChildAt).firstOrNull { it.top >= 0 } ?: return ""
        return "${list.getChildViewHolder(child).itemId}@${child.top}"
    }

    private fun rowTop(list: PamRecyclerList, id: Long): String {
        val child = (0 until list.childCount).map(list::getChildAt)
            .firstOrNull { list.getChildViewHolder(it).itemId == id } ?: return "gone"
        return "$id@${child.top}"
    }

    private fun dp(list: PamRecyclerList, value: Float): Int = (value * list.resources.displayMetrics.density).toInt()

    private fun withEmptyList(block: (PamRecyclerList) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            lateinit var list: PamRecyclerList
            main {
                list = PamRecyclerList(activity)
                activity.host.addView(list, FrameLayout.LayoutParams(600, 1_200))
                list.setRowHeight(ESTIMATE)
                set(list, emptyList(), estimated = true)
            }
            settle()
            block(list)
        } finally {
            main { activity.host.removeAllViews(); activity.finish() }
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val LEADING = 1L
        const val CACHED = 1_000L
        const val FRESH = 2_000L
        const val ESTIMATE = 64f
        const val HEADER = 40f
        const val ROW = 72f
    }
}
