package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A requested position is consumed when reached; later user scrolling owns it. */
@RunWith(AndroidJUnit4::class)
class PamScrollOffsetRequestInstrumentedTest {
    @Test
    fun reachedInitialHorizontalOffsetDoesNotReplayWhenLastPageRelayouts() = withScroll(true) { fixture ->
        fixture.main {
            fixture.scroll.setContentOffsetX(0f)
            assertEquals(0, fixture.scroll.snapshotOffsetPixels().first)
            // Native drag/settle owns this position. An active-page update then
            // requests layout, as the gallery changes its image and counter.
            fixture.scroll.getChildAt(0).scrollTo(300, 0)
            assertEquals(300, fixture.scroll.snapshotOffsetPixels().first)
            fixture.content.layoutParams = fixture.content.layoutParams.apply { height = 401 }
            fixture.layout()
            assertEquals("Completed initial request must not replace the user's last page", 300, fixture.scroll.snapshotOffsetPixels().first)
        }
        fixture.idle()
        fixture.main { assertEquals(300, fixture.scroll.snapshotOffsetPixels().first) }
    }

    @Test
    fun reachedInitialVerticalOffsetDoesNotReplayAtTheEnd() = withScroll(false) { fixture ->
        fixture.main {
            fixture.scroll.setContentOffsetY(0f)
            fixture.scroll.getChildAt(0).scrollTo(0, 400)
            assertEquals(400, fixture.scroll.snapshotOffsetPixels().second)
            fixture.content.layoutParams = fixture.content.layoutParams.apply { width = 301 }
            fixture.layout()
            assertEquals("The same request contract applies on both axes", 400, fixture.scroll.snapshotOffsetPixels().second)
        }
        fixture.idle()
        fixture.main { assertEquals(400, fixture.scroll.snapshotOffsetPixels().second) }
    }

    @Test
    fun requestBeforeContentExistsWaitsForItsOffsetThenStopsReplaying() = withScroll(true, 300, 300) { fixture ->
        fixture.main {
            assertEquals("A not-yet-reachable request is clamped until content grows", 0, fixture.scroll.snapshotOffsetPixels().first)
            fixture.content.layoutParams = fixture.content.layoutParams.apply { width = 600 }
            fixture.layout()
        }
        fixture.idle()
        fixture.main {
            assertEquals("The pending request must survive initial short content", 300, fixture.scroll.snapshotOffsetPixels().first)
            fixture.scroll.getChildAt(0).scrollTo(0, 0)
            // insert() also applies pending offsets. A completed request must
            // stay consumed when a later child or media decoration arrives.
            fixture.scroll.insert(View(fixture.activity).apply {
                layoutParams = FrameLayout.LayoutParams(1, 1)
            })
            fixture.layout()
            assertEquals("A reached request must not snap back after another insert", 0, fixture.scroll.snapshotOffsetPixels().first)
        }
        fixture.idle()
        fixture.main { assertEquals(0, fixture.scroll.snapshotOffsetPixels().first) }
    }

    private class Fixture(
        val instrumentation: Instrumentation,
        val activity: PamTestActivity,
        val scroll: PamScrollContainer,
        val content: View,
    ) {
        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun idle() = instrumentation.waitForIdleSync()
        fun layout() {
            content.requestLayout()
            val exactly = View.MeasureSpec.EXACTLY
            activity.host.measure(View.MeasureSpec.makeMeasureSpec(300, exactly), View.MeasureSpec.makeMeasureSpec(400, exactly))
            activity.host.layout(0, 0, 300, 400)
        }
    }

    private fun withScroll(horizontal: Boolean, contentWidth: Int = 600, initialOffsetPx: Int? = null, block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(
            instrumentation.targetContext, PamTestActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PamTestActivity
        try {
            lateinit var fixture: Fixture
            instrumentation.runOnMainSync {
                val scroll = PamScrollContainer(activity).apply { setHorizontal(horizontal) }
                if (initialOffsetPx != null) {
                    scroll.setContentOffsetX(initialOffsetPx / scroll.resources.displayMetrics.density)
                }
                val content = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(if (horizontal) contentWidth else 300, if (horizontal) 400 else 800)
                }
                scroll.insert(content)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                fixture = Fixture(instrumentation, activity, scroll, content)
                fixture.layout()
            }
            instrumentation.waitForIdleSync()
            block(fixture)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
