package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Self-animating spinners tick only while their host is on screen: a spinner
 * created (and started) by the renderer but never attached, hidden or
 * detached must not drive Choreographer frames.
 */
@RunWith(AndroidJUnit4::class)
class PamOffscreenSpinnerInstrumentedTest {
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    @Test
    fun activityIndicatorAnimatesOnlyWhileShown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch(instrumentation)
        try {
            instrumentation.runOnMainSync {
                val indicator = PamActivityIndicator(activity)
                val drawable = (indicator.getChildAt(0) as PamVuetifySpinner).indeterminateDrawable
                    as PamVuetifySpinnerDrawable
                assertTrue("animating is requested from creation", drawable.isRunning)
                assertFalse("a never-attached spinner must not tick", drawable.isTicking)

                activity.host.addView(indicator, FrameLayout.LayoutParams(80, 80))
                assertTrue("attached and shown, the spinner ticks", drawable.isTicking)

                activity.host.visibility = View.GONE
                assertFalse("a hidden ancestor pauses the spinner", drawable.isTicking)
                activity.host.visibility = View.VISIBLE
                assertTrue("shown again, the spinner resumes", drawable.isTicking)

                indicator.setAnimating(false)
                assertFalse(drawable.isTicking)
                indicator.setAnimating(true)
                assertTrue(drawable.isTicking)

                activity.host.removeView(indicator)
                assertFalse("a detached spinner must not tick", drawable.isTicking)
                assertTrue("detaching keeps the request for a later attach", drawable.isRunning)
                activity.host.addView(indicator, FrameLayout.LayoutParams(80, 80))
                assertTrue(drawable.isTicking)
                activity.host.removeView(indicator)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun buttonLoadingArcAnimatesOnlyWhileShown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launch(instrumentation)
        lateinit var button: Button
        lateinit var arc: PamButtonLoadingDrawable
        // The arc resumes from its next draw: let a frame pass between steps.
        fun step(block: () -> Unit) {
            instrumentation.runOnMainSync(block)
            Thread.sleep(FRAME_WAIT_MS)
            instrumentation.waitForIdleSync()
        }
        try {
            step {
                button = Button(activity)
                arc = PamButtonLoadingDrawable(activity, 40, Color.BLACK)
                button.setCompoundDrawables(arc, null, null, null)
                arc.start()
            }
            instrumentation.runOnMainSync {
                assertFalse("a loading button off screen must not tick", arc.isTicking)
            }
            step { activity.host.addView(button, FrameLayout.LayoutParams(200, 80)) }
            instrumentation.runOnMainSync { assertTrue("attached and shown, the arc ticks", arc.isTicking) }
            step { button.visibility = View.INVISIBLE }
            instrumentation.runOnMainSync { assertFalse("a hidden button pauses the arc", arc.isTicking) }
            step { button.visibility = View.VISIBLE }
            instrumentation.runOnMainSync { assertTrue("shown again, the arc resumes", arc.isTicking) }
            step { activity.host.removeView(button) }
            instrumentation.runOnMainSync { assertFalse("a detached loading button must not tick", arc.isTicking) }
            step {
                arc.stop()
                activity.host.addView(button, FrameLayout.LayoutParams(200, 80))
            }
            instrumentation.runOnMainSync { assertFalse("a stopped arc stays stopped", arc.isTicking) }
            step { activity.host.removeView(button) }
        } finally {
            activity.finish()
        }
    }

    private fun launch(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity

    private companion object {
        const val FRAME_WAIT_MS = 120L
    }
}
