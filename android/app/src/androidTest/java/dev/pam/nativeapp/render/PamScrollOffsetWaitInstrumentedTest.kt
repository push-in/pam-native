package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A requested content offset on a scroll view that is not laid out yet (GONE,
 * or with no content) waits for its next layout instead of re-posting itself
 * on the UI thread every loop iteration.
 */
@RunWith(AndroidJUnit4::class)
class PamScrollOffsetWaitInstrumentedTest {
    @Test
    fun unlaidOffsetRequestWaitsForLayoutWithoutSpinning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch(instrumentation)
        lateinit var scroll: PamScrollContainer
        try {
            instrumentation.runOnMainSync {
                scroll = PamScrollContainer(activity)
                scroll.visibility = View.GONE
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 300))
                scroll.insert(View(activity).apply { minimumHeight = 2_000 })
                scroll.setContentOffsetY(100f)
            }
            Thread.sleep(200)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse("a GONE scroll view is never laid out", scroll.isLaidOut)
                assertTrue("the request waits for a layout", field(scroll, "offsetAwaitsLayout"))
                assertFalse("no runnable keeps re-posting itself", field(scroll, "offsetScheduled"))
                scroll.visibility = View.VISIBLE
            }
            Thread.sleep(200)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val expected = (100f * activity.resources.displayMetrics.density + 0.5f).toInt()
                assertEquals("the first layout applies the offset", expected, scroll.getChildAt(0).scrollY)
                assertFalse(field(scroll, "offsetAwaitsLayout"))
            }
        } finally {
            activity.finish()
        }
    }

    private fun field(target: Any, name: String): Boolean =
        PamScrollContainer::class.java.getDeclaredField(name).apply { isAccessible = true }.getBoolean(target)

    private fun launch(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
}
