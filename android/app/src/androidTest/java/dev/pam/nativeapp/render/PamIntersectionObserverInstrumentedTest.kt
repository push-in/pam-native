package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamIntersectionObserverInstrumentedTest {
    @Test
    fun scrollingRecyclesVisibilityWithoutEmittingPerPixelEvents() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch(instrumentation)
        val changes = mutableListOf<Boolean>()
        lateinit var scroll: ScrollView
        lateinit var content: FrameLayout
        lateinit var cell: View
        lateinit var observer: PamIntersectionObserver
        try {
            instrumentation.runOnMainSync {
                scroll = ScrollView(activity)
                content = FrameLayout(activity).apply { minimumHeight = 1200 }
                cell = View(activity)
                content.addView(cell, FrameLayout.LayoutParams(160, 160))
                scroll.addView(content, FrameLayout.LayoutParams(300, 1200))
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 300))
                activity.host.measure(exactly(300), exactly(300))
                activity.host.layout(0, 0, 300, 300)
                observer = PamIntersectionObserver(cell, changes::add)
                // Exercise the actual ViewTreeObserver callback, with fixed
                // cell layout but a moving ancestor scroll viewport.
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true), changes)
                for (offset in 1..100) {
                    scroll.scrollTo(0, offset)
                    cell.viewTreeObserver.dispatchOnPreDraw()
                }
                assertEquals(listOf(true), changes)
                scroll.scrollTo(0, 200)
                assertEquals(200, scroll.scrollY)
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true, false), changes)
                scroll.scrollTo(0, 0)
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true, false, true), changes)
                content.removeView(cell)
                assertEquals(listOf(true, false, true, false), changes)
                content.addView(cell, FrameLayout.LayoutParams(160, 160))
                activity.host.measure(exactly(300), exactly(300))
                activity.host.layout(0, 0, 300, 300)
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true, false, true, false, true), changes)
                observer.close()
                assertEquals(listOf(true, false, true, false, true, false), changes)
                scroll.scrollTo(0, 200)
                cell.viewTreeObserver.dispatchOnPreDraw()
                scroll.scrollTo(0, 0)
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(6, changes.size)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun aFlingHoldsEdgesUntilTheListRestsThenReportsWhereItStopped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch(instrumentation)
        val changes = mutableListOf<Boolean>()
        lateinit var list: RecyclerView
        lateinit var cell: View
        try {
            instrumentation.runOnMainSync {
                cell = View(activity)
                val row = FrameLayout(activity).apply {
                    addView(cell, FrameLayout.LayoutParams(160, 160))
                }
                list = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 40
                        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                            object : RecyclerView.ViewHolder(
                                if (viewType == 1) row else View(activity),
                            ) {}
                        override fun getItemViewType(position: Int) = if (position == 0) 1 else 0
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            holder.itemView.layoutParams = RecyclerView.LayoutParams(300, 160)
                        }
                    }
                }
                activity.host.addView(list, FrameLayout.LayoutParams(300, 300))
                activity.host.measure(exactly(300), exactly(300))
                activity.host.layout(0, 0, 300, 300)
                PamIntersectionObserver(cell, changes::add)
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true), changes)

                // A fling sweeps the cell out and back in: nothing is reported.
                list.fling(0, 8000)
                assertEquals(RecyclerView.SCROLL_STATE_SETTLING, list.scrollState)
                cell.translationY = -400f
                cell.viewTreeObserver.dispatchOnPreDraw()
                cell.translationY = 0f
                cell.viewTreeObserver.dispatchOnPreDraw()
                cell.translationY = -400f
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true), changes)

                // It rests: one edge, for where the cell actually stopped.
                list.stopScroll()
                assertEquals(listOf(true, false), changes)

                // At rest (or under a finger) edges are live again.
                cell.translationY = 0f
                cell.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(listOf(true, false, true), changes)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun onlyASettlingListHoldsEdges() {
        assertEquals(true, holdsVisibilityEdges(RecyclerView.SCROLL_STATE_SETTLING))
        assertEquals(false, holdsVisibilityEdges(RecyclerView.SCROLL_STATE_DRAGGING))
        assertEquals(false, holdsVisibilityEdges(RecyclerView.SCROLL_STATE_IDLE))
    }

    private fun launch(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
