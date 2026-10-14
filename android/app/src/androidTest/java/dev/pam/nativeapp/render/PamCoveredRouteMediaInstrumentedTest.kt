package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covered-route policy (1.41.0): a player whose route is covered by a
 * full-screen route, or whose tab is inactive, frees its decoder, player
 * and video surface, and comes back at the same position when visible.
 * This is what Zé Chat's ReleasesMediaWhenCovered trait did by hand.
 */
@RunWith(AndroidJUnit4::class)
class PamCoveredRouteMediaInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pushedFullScreenRouteReleasesThePlayerAndPopRestoresItsPosition() = withFixture { f ->
        val navigation = f.navigation()
        val feed = FrameLayout(f.activity)
        val loops = View(f.activity)
        main {
            feed.addView(f.media, FrameLayout.LayoutParams(200, 120))
            navigation.insert(feed, 0)
            f.media.setAutoPlay(true)
            f.media.setSource(f.source)
        }
        awaitPlaying(f.media, true)
        SystemClock.sleep(900)
        var before = 0
        main {
            before = f.media.currentPosition
            navigation.operation = 2
            navigation.insert(loops, 1)
            navigation.navigate(1)
        }
        await("covered route releases its player") {
            f.media.releasedForCover && field(f.media, "preparedPlayer") == null && field(f.media, "exo") == null
        }
        main {
            assertEquals(View.INVISIBLE, feed.visibility)
            assertTrue(PamRouteCoverage.isCovered(f.media))
            assertFalse(f.media.isPlaying)
            assertNull("The video surface is freed too", field(f.media, "videoSurface"))
        }
        main {
            navigation.operation = 3
            navigation.navigate(2)
        }
        await("the route below is visible again") { !f.media.releasedForCover }
        main { navigation.removeRoute(loops) }
        awaitPlaying(f.media, true)
        main {
            assertTrue(
                "Playback resumes where it was covered (${f.media.currentPosition} ms, covered at $before ms)",
                f.media.currentPosition >= before - 250,
            )
        }
    }

    @Test fun inactiveTabSceneReleasesAndReselectionRestores() = withFixture { f ->
        lateinit var tabs: PamTabHost
        main {
            tabs = PamTabHost(f.activity)
            val feed = FrameLayout(f.activity).apply { addView(f.media, FrameLayout.LayoutParams(200, 120)) }
            tabs.insertScene(feed, 0)
            tabs.insertScene(View(f.activity), 1)
            tabs.configure(
                """[{"name":"feed","label":"Feed","badge":null},{"name":"chat","label":"Chat","badge":null}]""",
                1, 1, Color.BLACK, Color.GRAY, Color.WHITE, Color.BLACK, false,
            )
            f.activity.host.addView(tabs, FrameLayout.LayoutParams(400, 600))
            f.media.setAutoPlay(true)
            f.media.setSource(f.source)
        }
        awaitPlaying(f.media, true)
        main { tabs.selectTab(2) }
        await("inactive tab releases its player") { f.media.releasedForCover && field(f.media, "preparedPlayer") == null }
        main { tabs.selectTab(1) }
        await("selected tab restores") { !f.media.releasedForCover }
        awaitPlaying(f.media, true)
    }

    @Test fun keepAliveWhenCoveredOptsOut() = withFixture { f ->
        val navigation = f.navigation()
        val feed = FrameLayout(f.activity)
        main {
            feed.addView(f.media, FrameLayout.LayoutParams(200, 120))
            navigation.insert(feed, 0)
            f.media.setKeepAliveWhenCovered(true)
            f.media.setAutoPlay(true)
            f.media.setSource(f.source)
        }
        awaitPlaying(f.media, true)
        main {
            navigation.operation = 2
            navigation.insert(View(f.activity), 1)
            navigation.navigate(1)
        }
        await("route covered") { feed.visibility == View.INVISIBLE }
        SystemClock.sleep(200)
        main {
            assertFalse(f.media.releasedForCover)
            assertTrue("Opted-out player stays allocated (paused while hidden)", field(f.media, "preparedPlayer") != null)
        }
    }

    @Test fun routeFocusTrackerReportsOnlyChanges() {
        val tracker = RouteFocusTracker()
        assertNull(tracker.update(false))
        assertEquals(true, tracker.update(true))
        assertNull(tracker.update(true))
        assertEquals(false, tracker.update(false))
    }

    @Test fun mediaClockAndProgressFollowThePlayerNatively() = withFixture { f ->
        lateinit var clock: TextView
        lateinit var bar: View
        main {
            f.activity.host.addView(f.media, FrameLayout.LayoutParams(200, 120))
            clock = TextView(f.activity).apply {
                text = "placeholder"
                setTag(R.id.pam_media_clock, "reel")
                setTag(R.id.pam_media_clock_format, PamMediaClock.REMAINING)
            }
            bar = View(f.activity).apply { setTag(R.id.pam_media_progress, "reel"); scaleX = 0f }
            f.activity.host.addView(clock, FrameLayout.LayoutParams(200, 60))
            f.activity.host.addView(bar, FrameLayout.LayoutParams(200, 8))
            f.media.setTag(R.id.pam_native_ref, "reel")
            f.media.boundViews = { listOf(clock, bar) }
            f.media.setAutoPlay(true)
            f.media.setSource(f.source)
        }
        awaitPlaying(f.media, true)
        await("clock shows the remaining time") { clock.text.toString() == "0:05" || clock.text.toString() == "0:04" }
        SystemClock.sleep(1_300)
        main {
            assertNotEquals("placeholder", clock.text.toString())
            assertTrue("remaining time counts down (${clock.text})", clock.text.toString() in setOf("0:04", "0:03"))
            assertTrue("progress fill advances (${bar.scaleX})", bar.scaleX > 0.1f)
            assertEquals(0f, bar.pivotX)
        }
    }

    private class Fixture(
        val activity: PamTestActivity,
        val media: PamMediaView,
        val source: String,
        private val instrumentation: android.app.Instrumentation,
    ) {
        fun navigation(): PamNavigationHost {
            lateinit var navigation: PamNavigationHost
            instrumentation.runOnMainSync {
                PamMotionPolicy.reduceMotionOverride = true
                navigation = PamNavigationHost(activity)
                activity.host.addView(navigation, FrameLayout.LayoutParams(400, 600))
            }
            return navigation
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val cache = NativeMediaFileCache(activity)
        val loader = NativeImageLoader(activity)
        val sample = File(activity.cacheDir, "pam-covered-route.wav")
        val samples = 8_000 * 5 * 2
        sample.writeBytes(ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples)
        }.array())
        lateinit var media: PamMediaView
        try {
            main {
                media = PamMediaView(activity, cache, loader)
                media.setMuted(true)
            }
            block(Fixture(activity, media, sample.toURI().toString(), instrumentation))
        } finally {
            main {
                PamMotionPolicy.reduceMotionOverride = null
                activity.host.removeAllViews()
                activity.finish()
            }
            loader.close()
            cache.close()
            sample.delete()
        }
    }

    private fun field(media: PamMediaView, name: String): Any? =
        PamMediaView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(media)

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(25)
        }
        main { assertTrue(label, condition()) }
    }

    private fun awaitPlaying(media: PamMediaView, expected: Boolean) =
        await("Playback should be $expected") { media.isPlaying == expected }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
