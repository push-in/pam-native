package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `autoPlayVisible` + `autoPlayGroup`: of two players in one group only the
 * one with at least half of it on screen is prepared and plays; moving the
 * other into view hands playback over natively, pauses the first at once and
 * releases its player after the grace period.
 */
@RunWith(AndroidJUnit4::class)
class PamVisibleAutoplayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun theVisiblePlayerOfAGroupPlaysAndHandsOverWhenAnotherShows() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val cache = NativeMediaFileCache(activity)
        val loader = NativeImageLoader(activity)
        val sample = File(activity.cacheDir, "pam-autoplay.mp4")
        instrumentation.context.assets.open("pam-video/red-blue.mp4").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        lateinit var viewport: FrameLayout
        lateinit var first: PamMediaView
        lateinit var second: PamMediaView
        fun place(view: PamMediaView, top: Int) {
            view.layoutParams = FrameLayout.LayoutParams(320, 240).apply { topMargin = top }
        }
        fun waitUntil(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                var met = false
                instrumentation.runOnMainSync { met = condition() }
                if (met) return true
                SystemClock.sleep(50)
            }
            return false
        }
        try {
            instrumentation.runOnMainSync {
                viewport = FrameLayout(activity)
                activity.host.addView(viewport, FrameLayout.LayoutParams(320, 480))
                first = PamMediaView(activity, cache, loader)
                second = PamMediaView(activity, cache, loader)
                for (view in listOf(first, second)) {
                    view.setMuted(true)
                    view.setLoop(true)
                    view.setAutoPlay(true)
                    view.setAutoPlayGroup("feed")
                    view.setAutoPlayVisible(0.5f)
                    view.setSource(sample.toURI().toString())
                }
                viewport.addView(first, FrameLayout.LayoutParams(320, 240))
                // 80 of 240 px inside the viewport: a third, below the threshold.
                viewport.addView(second, FrameLayout.LayoutParams(320, 240).apply { topMargin = 400 })
            }
            assertTrue("the visible player plays", waitUntil { first.autoplayGranted && first.isPlaying })
            instrumentation.runOnMainSync {
                assertFalse("the third-visible player waits", second.autoplayGranted)
                assertFalse("no player is created without the grant", second.holdsPlayer)
            }

            instrumentation.runOnMainSync {
                place(first, -200)
                place(second, 120)
            }
            assertTrue("playback moves to the player now on screen", waitUntil { second.autoplayGranted && second.isPlaying })
            instrumentation.runOnMainSync {
                assertFalse(first.autoplayGranted)
                assertFalse("the previous player paused", first.isPlaying)
            }
            assertTrue(
                "the paused player is released after the grace period",
                waitUntil { !first.holdsPlayer },
            )

            instrumentation.runOnMainSync { second.setAutoPlay(false) }
            assertTrue("autoPlay=false keeps a granted player paused", waitUntil { !second.isPlaying })
        } finally {
            instrumentation.runOnMainSync {
                activity.host.removeAllViews()
                activity.finish()
            }
            loader.close()
            cache.close()
            sample.delete()
        }
    }
}
