package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A video cell attached and detached again and again while its player
 * prepares (a list flung past it): the player lets go of its surface before
 * the surface is released, so no codec ever writes into an abandoned queue
 * (no CodecException / "flush() ... at Released state" reaching onError).
 */
@RunWith(AndroidJUnit4::class)
class PamMediaChurnInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun attachingAndDetachingDuringPrepareReportsNoPlaybackError() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val cache = NativeMediaFileCache(activity)
        val loader = NativeImageLoader(activity)
        val sample = File(activity.cacheDir, "pam-churn.mp4")
        instrumentation.context.assets.open("pam-video/red-blue.mp4").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val errors = mutableListOf<String>()
        lateinit var media: PamMediaView
        try {
            instrumentation.runOnMainSync {
                media = PamMediaView(activity, cache, loader)
                media.setMuted(true)
                media.onError = { errors += it }
                media.setSource(sample.toURI().toString())
            }
            repeat(24) { round ->
                instrumentation.runOnMainSync {
                    activity.host.addView(media, FrameLayout.LayoutParams(320, 240))
                }
                // Detach at different points of the prepare (0 to ~230 ms).
                SystemClock.sleep((round % 8) * 33L)
                instrumentation.runOnMainSync { activity.host.removeView(media) }
            }
            // Let every pending release and codec teardown finish.
            SystemClock.sleep(1_500)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("playback errors: $errors", emptyList<String>(), errors.toList())
            }
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
