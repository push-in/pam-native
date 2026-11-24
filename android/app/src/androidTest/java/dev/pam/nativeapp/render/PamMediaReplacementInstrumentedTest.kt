package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same regression fixtures run against both the old APK and the repaired player. */
@RunWith(AndroidJUnit4::class)
class PamMediaReplacementInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun mediaPlayerStopsBeforeInvalidSourceReportsError() = rejectedSource(0, cacheOnly = false)
    @Test fun exoPlayerStopsBeforeInvalidSourceReportsError() = rejectedSource(4, cacheOnly = false)
    @Test fun mediaPlayerStopsWhenReplacementMissesCacheOnly() = rejectedSource(0, cacheOnly = true)
    @Test fun exoPlayerStopsWhenReplacementMissesCacheOnly() = rejectedSource(4, cacheOnly = true)
    @Test fun mediaPlayerPollingSleepsWhenPausedAndEnded() = pollingLifecycle(0)
    @Test fun exoPlayerPollingSleepsWhenPausedAndEnded() = pollingLifecycle(4)
    @Test fun mediaPlayerPosterRenewalPreservesPausedFrame() = posterRenewal(0)
    @Test fun exoPlayerPosterRenewalPreservesPausedFrame() = posterRenewal(4)
    @Test fun mediaPlayerProgressCannotWriteTheReplacedSourcesClock() = progressReplacement(0)
    @Test fun exoPlayerProgressCannotWriteTheReplacedSourcesClock() = progressReplacement(4)

    @Test fun playbackErrorRestoresPosterBeforeCallbackAndPreservesCallbackReplacement() = withMedia(4) { media, source, directory ->
        val request = posterRequest(directory)
        val ready = CountDownLatch(1)
        main {
            media.setThumbnail(request)
            media.setAutoPlay(false)
            media.seek(1.5)
            media.onReady = { ready.countDown() }
            media.setSource(source)
        }
        assertTrue("paused video prepares", ready.await(10, TimeUnit.SECONDS))
        awaitMain("paused frame is shown") { poster(media).visibility == View.GONE && framePixel(media) != null }
        val replacement = File(directory, "replacement.mp4")
        File(directory, "red-blue.mp4").copyTo(replacement)
        val replacementReady = CountDownLatch(1)
        main {
            // Drop old decoded poster pixels while retaining the latest request.
            media.setThumbnail(request.copy(mediaCacheKey = "error-poster"))
            media.onError = {
                assertEquals("error exposes its poster before notifying the app", View.VISIBLE, poster(media).visibility)
                media.onReady = { replacementReady.countDown() }
                media.setSource(replacement.toURI().toString())
            }
            // Deliver a deterministic decoder failure through the real generation-guarded listener.
            val generation = PamMediaView::class.java.getDeclaredField("playerGeneration")
                .apply { isAccessible = true }.getLong(media)
            val listener = PamMediaView::class.java.getDeclaredMethod("exoListener", java.lang.Long.TYPE)
                .apply { isAccessible = true }.invoke(media, generation) as PamExoPlayback.Listener
            listener.onError("fixture decoder failure", false)
            assertTrue("old error cleanup must not release the replacement", media.holdsPlayer)
        }
        assertTrue("callback replacement prepares", replacementReady.await(10, TimeUnit.SECONDS))
    }

    private fun progressReplacement(buffer: Int) = withMedia(buffer) { media, source, _ ->
        val changed = CountDownLatch(1)
        lateinit var clock: TextView
        main {
            clock = TextView(media.context).apply { setTag(R.id.pam_media_clock, "replacement-clock") }
            media.setTag(R.id.pam_native_ref, "replacement-clock")
            media.boundViews = { listOf(clock) }
        }
        start(media, source)
        main {
            media.onProgress = { _, _ ->
                media.setSource("")
                clock.text = "replacement"
                changed.countDown()
            }
        }
        assertTrue("progress callback replaces source", changed.await(5, TimeUnit.SECONDS))
        main {
            assertEquals("old callback cannot overwrite the replacement clock", "replacement", clock.text.toString())
            assertFalse(media.holdsPlayer)
        }
    }

    private fun rejectedSource(buffer: Int, cacheOnly: Boolean) = withMedia(buffer) { media, source, _ ->
        val errors = AtomicInteger()
        val progress = AtomicInteger()
        main {
            media.onError = { errors.incrementAndGet() }
            media.onProgress = { _, _ -> progress.incrementAndGet() }
            if (cacheOnly) {
                media.setCacheRequest(MediaCacheRequest(
                    source = "", policy = MEDIA_CACHE_CACHE_ONLY, key = "replacement-${System.nanoTime()}",
                    maxAgeMs = 0, maxBytes = 0, checksum = null, pinOffline = false,
                    streaming = false, downloadWhilePlaying = false,
                ))
            }
        }
        start(media, source)
        main {
            media.setSource(if (cacheOnly) "https://127.0.0.1/absent.mp4" else "missing-${System.nanoTime()}.mp4")
            assertEquals("new source reports one failure", 1, errors.get())
            assertFalse("old decoder must be released before the source error", media.holdsPlayer)
            assertFalse("old media must not remain playing", media.isPlaying())
        }
        val afterFailure = progress.get()
        SystemClock.sleep(650)
        assertEquals("old source must not emit progress under the replacement", afterFailure, progress.get())
        assertEquals("invalid/cache-only sources do not schedule playback retries", 1, errors.get())
    }

    private fun pollingLifecycle(buffer: Int) = withMedia(buffer) { media, source, _ ->
        val bindingQueries = AtomicInteger()
        val ended = CountDownLatch(1)
        main {
            media.setTag(R.id.pam_native_ref, "replacement-clock")
            media.boundViews = { bindingQueries.incrementAndGet(); emptyList() }
            media.onProgress = { _, _ -> }
            media.onEnd = { ended.countDown() }
        }
        start(media, source)
        awaitMain("playing player polls its clock") { bindingQueries.get() >= 3 }
        main { media.pause() }
        awaitMain("manual pause applies") { !media.isPlaying() }
        SystemClock.sleep(350)
        val pausedQueries = bindingQueries.get()
        SystemClock.sleep(750)
        assertEquals("paused player has no periodic binding queries", pausedQueries, bindingQueries.get())
        main { media.seek(0.5) }
        val afterSeek = bindingQueries.get()
        SystemClock.sleep(350)
        assertEquals("paused seek updates once without starting a polling loop", afterSeek, bindingQueries.get())
        main { media.start() }
        awaitMain("play restarts progress polling") { media.isPlaying() && bindingQueries.get() > afterSeek }
        main {
            media.setLoop(false)
            media.seekTo((media.getDuration() - 150).coerceAtLeast(0))
        }
        assertTrue("completion callback is retained", ended.await(8, TimeUnit.SECONDS))
        SystemClock.sleep(350)
        val endedQueries = bindingQueries.get()
        SystemClock.sleep(750)
        assertEquals("ended player has no periodic binding queries", endedQueries, bindingQueries.get())
    }

    private fun posterRenewal(buffer: Int) = withMedia(buffer) { media, source, directory ->
        val request = posterRequest(directory)
        val ready = CountDownLatch(1)
        main {
            media.setThumbnail(request)
            media.setAutoPlay(false)
            media.seek(1.5)
            media.onReady = { ready.countDown() }
            media.setSource(source)
        }
        assertTrue("paused video prepares", ready.await(10, TimeUnit.SECONDS))
        awaitMain("first paused frame replaces its poster") {
            poster(media).visibility == View.GONE && framePixel(media) != null
        }
        var before = 0
        main {
            before = checkNotNull(framePixel(media))
            media.setThumbnail(request.copy(mediaCacheKey = "renewed-poster"))
            assertEquals("renewed poster never covers a rendered frame", View.GONE, poster(media).visibility)
        }
        SystemClock.sleep(500)
        main {
            assertFalse("poster renewal must preserve manual pause", media.isPlaying())
            assertEquals("late thumbnail decode must not cover the paused frame", View.GONE, poster(media).visibility)
            assertEquals("the displayed texture stays at the paused frame", before, checkNotNull(framePixel(media)))
            // The deferred poster must still be usable when that frame goes away.
            media.setSource("")
        }
        awaitMain("released player restores the latest poster") {
            poster(media).visibility == View.VISIBLE && poster(media).drawable != null
        }
    }

    private fun posterRequest(directory: File): NativeImageRequest {
        val image = File(directory, "replacement-poster.png")
        Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.RED)
                image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
        return NativeImageRequest(image.toURI().toString(), fadeDurationMs = 0, releaseWhenHidden = true)
    }

    private fun start(media: PamMediaView, source: String) {
        val ready = CountDownLatch(1)
        main {
            media.onReady = { ready.countDown() }
            media.setLoop(true)
            media.setAutoPlay(true)
            media.setSource(source)
        }
        assertTrue("local video prepares", ready.await(10, TimeUnit.SECONDS))
        awaitMain("local video starts") { media.isPlaying() }
    }

    private fun poster(media: PamMediaView): PamImageView =
        (0 until media.childCount).map(media::getChildAt).filterIsInstance<PamImageView>().single()

    private fun framePixel(media: PamMediaView): Int? =
        (media.surfaceView as TextureView).getBitmap(8, 8)?.let { bitmap ->
            try { bitmap.getPixel(4, 4) } finally { bitmap.recycle() }
        }

    private fun awaitMain(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(40)
        }
        main { assertTrue(message, condition()) }
    }

    private fun withMedia(buffer: Int, block: (PamMediaView, String, File) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val directory = File(activity.cacheDir, "pam-replacement-${System.nanoTime()}").apply { mkdirs() }
        val clip = File(directory, "red-blue.mp4")
        instrumentation.context.assets.open("pam-video/red-blue.mp4").use { input ->
            clip.outputStream().use(input::copyTo)
        }
        val cache = NativeMediaFileCache(activity)
        val loader = NativeImageLoader(activity)
        lateinit var media: PamMediaView
        val previousBackground = PamMediaView.appInBackground
        try {
            main {
                // This lightweight host has no runtime to resume a prior real Activity's media gate.
                PamMediaView.appInBackground = false
                media = PamMediaView(activity, cache, loader)
                media.setMuted(true)
                media.setForwardBufferSeconds(buffer)
                activity.host.addView(media, FrameLayout.LayoutParams(240, 160))
            }
            instrumentation.waitForIdleSync()
            block(media, clip.toURI().toString(), directory)
        } finally {
            main {
                activity.host.removeAllViews()
                loader.close()
                activity.finish()
                PamMediaView.appInBackground = previousBackground
            }
            cache.close()
            directory.deleteRecursively()
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
