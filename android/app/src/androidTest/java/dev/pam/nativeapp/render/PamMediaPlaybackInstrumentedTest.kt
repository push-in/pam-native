package dev.pam.nativeapp.render

import android.content.Intent
import android.media.MediaPlayer
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamMediaPlaybackInstrumentedTest {
    @Test fun preparedAfterBackgroundWaitsForResumeAndRespectsManualPause() = withMedia { media, _, source ->
        val ready = CountDownLatch(1)
        main {
            media.onReady = { ready.countDown() }
            media.setAutoPlay(true)
            media.onHostPause()
            media.setPlaybackRate(2f)
            media.setSource(source)
        }
        assertTrue("Local media prepares in background", ready.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(150)
        assertStopped(media)
        main { media.start(); media.setPlaybackRate(1.5f) }
        SystemClock.sleep(150)
        assertStopped(media)
        main { media.onHostResume() }
        awaitPlaying(media, true)
        main { media.pause(); media.onHostPause(); media.onHostResume(); media.setAutoPlay(true) }
        awaitPlaying(media, false)
        SystemClock.sleep(150)
        assertStopped(media)
    }

    @Test fun hiddenParentBlocksLatePreparationAndDetachReleasesThePlayer() = withMedia { media, parent, source ->
        val ready = CountDownLatch(1)
        main {
            parent.visibility = View.INVISIBLE
            media.onReady = { ready.countDown() }
            media.setAutoPlay(true)
            media.setLoop(true)
            media.setSource(source)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(150)
        assertStopped(media)
        main { parent.visibility = View.VISIBLE }
        awaitPlaying(media, true)
        main { parent.visibility = View.GONE }
        awaitPlaying(media, false)
        main { parent.visibility = View.VISIBLE }
        awaitPlaying(media, true)
        var player: MediaPlayer? = null
        main {
            player = PamMediaView::class.java.getDeclaredField("preparedPlayer").apply { isAccessible = true }.get(media) as MediaPlayer
            parent.removeView(media)
            media.onHostResume()
            media.start()
        }
        SystemClock.sleep(200)
        assertFalse("Detached native player must be stopped or released", runCatching { player?.isPlaying == true }.getOrDefault(false))
        assertStopped(media)
    }

    @Test fun createdAfterHostPauseDerivesInactiveStateFromItsLifecycleOwner() = withMedia(initiallyPaused = true) { media, parent, source ->
        val ready = CountDownLatch(1)
        main {
            media.onReady = { ready.countDown() }
            media.setAutoPlay(true)
            media.setSource(source)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(150)
        assertStopped(media)
        main {
            ((parent.context as PamTestActivity).lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
            media.onHostResume()
        }
        awaitPlaying(media, true)
    }

    private fun withMedia(initiallyPaused: Boolean = false, block: (PamMediaView, FrameLayout, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val cache = NativeMediaFileCache(activity)
        val loader = NativeImageLoader(activity)
        val sample = File(activity.cacheDir, "pam-playback-lifecycle.wav")
        val samples = 8_000 * 5 * 2
        sample.writeBytes(ByteBuffer.allocate(44 + samples).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8_000); putInt(16_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(samples)
        }.array())
        lateinit var media: PamMediaView
        lateinit var parent: FrameLayout
        try {
            main {
                if (initiallyPaused) (activity.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.STARTED
                parent = FrameLayout(activity)
                media = PamMediaView(activity, cache, loader)
                media.setMuted(true)
                parent.addView(media, FrameLayout.LayoutParams(200, 120))
                activity.host.addView(parent, FrameLayout.LayoutParams(200, 120))
            }
            instrumentation.waitForIdleSync()
            block(media, parent, sample.toURI().toString())
        } finally {
            main { activity.host.removeAllViews(); activity.finish() }
            loader.close()
            cache.close()
            sample.delete()
        }
    }

    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun assertStopped(media: PamMediaView) { main { assertFalse(media.isPlaying()) } }
    private fun awaitPlaying(media: PamMediaView, expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            var playing = false
            main { playing = media.isPlaying() }
            if (playing == expected) return
            SystemClock.sleep(20)
        }
        main { assertTrue("Playback should be $expected", media.isPlaying() == expected) }
    }
}
