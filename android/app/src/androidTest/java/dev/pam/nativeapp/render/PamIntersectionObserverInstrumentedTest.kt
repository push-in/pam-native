package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
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

    private fun launch(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
