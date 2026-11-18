package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * `blurRadius` on images (1.50.x): the loader decodes a blurred image to the
 * heap and blurs it on a worker, so the UI thread never reads a GPU bitmap
 * back (15 ms per image on a Mali-G76) nor blurs; the result matches the
 * former UI-thread blur of the HARDWARE decode, and a blurred image (a chat
 * spoiler) never shows a sharp frame.
 */
@RunWith(AndroidJUnit4::class)
class PamImageBlurInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: PamTestActivity
    private lateinit var loader: NativeImageLoader
    private lateinit var container: FrameLayout

    /** (on the UI thread, from a GPU bitmap) for every blur. */
    private val blurs = Collections.synchronizedList(ArrayList<Pair<Boolean, Boolean>>())

    @Before
    fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        loader = NativeImageLoader(context)
        instrumentation.runOnMainSync {
            container = FrameLayout(activity)
            activity.host.addView(container, FrameLayout.LayoutParams(400, 400))
        }
        PamBitmapBlur.blurObserver = { hardware -> blurs += (Looper.myLooper() == Looper.getMainLooper()) to hardware }
    }

    @After
    fun tearDown() {
        PamBitmapBlur.blurObserver = null
        instrumentation.runOnMainSync {
            loader.close()
            activity.finish()
        }
    }

    @Test
    fun aBlurredImageIsBlurredOffTheUiThreadWithoutReadbackAndLooksAsBefore() {
        val file = photo("pam-blur-look.jpg")
        val blurred = addImage(blur = 40f)
        // The former path: the sharp decode (a GPU bitmap on API 28+) blurred on the UI thread.
        val sharp = addImage(blur = 0f)
        load(blurred, file)
        load(sharp, file)
        assertTrue("blurred at least once: $blurs", blurs.isNotEmpty())
        assertTrue("never on the UI thread: $blurs", blurs.none { it.first })
        assertTrue("never from a GPU bitmap (readback): $blurs", blurs.none { it.second })
        lateinit var drawable: PamBlurredDrawable
        lateinit var hardware: Bitmap
        instrumentation.runOnMainSync {
            drawable = blurred.drawable as PamBlurredDrawable
            hardware = (sharp.drawable as BitmapDrawable).bitmap
        }
        assertTrue("the blurred one is a heap bitmap", !drawable.bitmap.isHardware)
        assertEquals("intrinsic size is the sharp decode's", hardware.width, drawable.intrinsicWidth)
        assertEquals(hardware.height, drawable.intrinsicHeight)
        // Before: the UI thread blurred the HARDWARE decode at the drawn σ.
        PamBitmapBlur.blurObserver = null
        val sigma = PamBitmapBlur.bitmapSigma(40f, 300, 300, hardware.width, hardware.height, ImageView.ScaleType.CENTER_CROP)
        assertEquals(sigma, drawable.blur.bitmapSigma, 0.001f)
        val before = PamBitmapBlur.blur(hardware, sigma)
        val after = drawable.bitmap
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var worst = 0
        for (y in 0 until after.height) {
            for (x in 0 until after.width) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                worst = maxOf(
                    worst,
                    abs(Color.red(a) - Color.red(b)),
                    abs(Color.green(a) - Color.green(b)),
                    abs(Color.blue(a) - Color.blue(b)),
                    abs(Color.alpha(a) - Color.alpha(b)),
                )
            }
        }
        assertTrue("pixels match the former blur (worst channel delta $worst)", worst <= 4)
    }

    @Test
    fun aSpoilerNeverShowsASharpFrameAndARecycledRowHitsTheCacheInTheSameFrame() {
        val file = photo("pam-blur-spoiler.jpg")
        val image = addImage(blur = 40f)
        var sharpFrames = 0
        val watcher = ViewTreeObserver.OnDrawListener {
            if (image.blurSigma > 0f && image.drawable != null && image.appliedBlur == null) sharpFrames++
        }
        instrumentation.runOnMainSync { container.viewTreeObserver.addOnDrawListener(watcher) }
        var loads = 0
        load(image, file) { loads++ }

        // Reveal: the blur stays until the sharp image arrives, without load events.
        instrumentation.runOnMainSync {
            image.setBlur(0f)
            assertNotNull("the blurred image stays while the sharp one decodes", image.appliedBlur)
        }
        awaitMain("the sharp image shows") { image.drawable != null && image.appliedBlur == null }
        // Hide again: the cached blur shows in this very call (no empty or sharp frame).
        instrumentation.runOnMainSync {
            image.setBlur(40f)
            assertNotNull("blurred from the memory cache at once", image.appliedBlur)
            assertTrue(image.showsWantedBlur())
        }

        // A recycled row bound to the same image paints in the binding call.
        val blursBefore = blurs.size
        val row = addImage(blur = 40f)
        instrumentation.runOnMainSync {
            row.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            row.layout(0, 0, 300, 300)
            loader.load(NativeImageRequest(source = "file://${file.absolutePath}", fadeDurationMs = 0), row, NativeImageCallbacks())
            assertNotNull("cache hit is synchronous", row.appliedBlur)
        }
        assertEquals("a cache hit blurs nothing", blursBefore, blurs.size)

        // Turning a blur on over a sharp image drops it in the same call.
        val plain = addImage(blur = 0f)
        load(plain, file)
        instrumentation.runOnMainSync {
            plain.setBlur(13f)
            assertTrue("sharp pixels go at once", plain.drawable == null || plain.appliedBlur != null)
        }
        awaitMain("the new blur shows") { plain.appliedBlur?.viewSigma == 13f }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { container.viewTreeObserver.removeOnDrawListener(watcher) }
        assertEquals("no frame drew a sharp image under a blur", 0, sharpFrames)
        assertEquals("blur changes fire no load events", 1, loads)
        assertTrue("never on the UI thread: $blurs", blurs.none { it.first })
    }

    @Test
    fun aResizedBlurredImageIsBlurredAgainOffTheUiThreadOnlyPastTheTolerance() {
        val file = photo("pam-blur-resize.jpg")
        val image = addImage(blur = 40f)
        load(image, file)
        val first = blurs.size
        // 1 px: the shown σ still fits, nothing is redone.
        resize(image, 301)
        assertEquals(first, blurs.size)
        assertTrue(runOnMain { image.showsWantedBlur() })
        resize(image, 150)
        awaitMain("re-blurred for 150 px") { image.showsWantedBlur() }
        assertTrue("blurred again: $blurs", blurs.size > first)
        assertTrue("never on the UI thread: $blurs", blurs.none { it.first })
    }

    private fun addImage(blur: Float): PamImageView {
        lateinit var image: PamImageView
        instrumentation.runOnMainSync {
            image = PamImageView(activity)
            image.setBlur(blur)
            container.addView(image, FrameLayout.LayoutParams(300, 300))
        }
        return image
    }

    private fun load(image: PamImageView, file: File, onLoad: () -> Unit = {}) {
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            loader.load(
                NativeImageRequest(source = "file://${file.absolutePath}", fadeDurationMs = 0),
                image,
                NativeImageCallbacks(onSuccess = { onLoad(); loaded.countDown() }),
            )
        }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun resize(image: PamImageView, edge: Int) {
        instrumentation.runOnMainSync { image.layoutParams = FrameLayout.LayoutParams(edge, edge) }
        instrumentation.waitForIdleSync()
    }

    private fun <T> runOnMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun awaitMain(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (runOnMain(condition)) return
            SystemClock.sleep(16)
        }
        throw AssertionError("timed out: $what")
    }

    /** A 1200x900 JPEG with hard red/blue/white edges, decoded to cover 300 px. */
    private fun photo(name: String): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(600f, 0f, 1200f, 900f, Paint().apply { color = Color.BLUE })
            drawRect(300f, 300f, 900f, 600f, Paint().apply { color = Color.WHITE })
        }
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        return file
    }
}
