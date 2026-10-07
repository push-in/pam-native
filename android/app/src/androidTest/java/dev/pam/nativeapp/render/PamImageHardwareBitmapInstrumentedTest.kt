package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.content.Intent
import dev.pam.nativeapp.PamTestActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hardware bitmaps (1.26.0): software canvases and engine-owned layout. */
@RunWith(AndroidJUnit4::class)
class PamImageHardwareBitmapInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun hardwareBitmapDrawsIntoASoftwareCanvas() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        instrumentation.runOnMainSync {
            val view = PamImageView(context)
            val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val hardware = requireNotNull(source.copy(Bitmap.Config.HARDWARE, false))
            view.setImageDrawable(BitmapDrawable(context.resources, hardware))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 100, 100)
            // A shared-element snapshot draws the view into a heap bitmap.
            val snapshot = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(snapshot))
            assertEquals(Color.RED, snapshot.getPixel(50, 50))
        }
    }

    @Test
    fun aNewDrawableDoesNotRequestLayoutOfAnEngineSizedView() {
        instrumentation.runOnMainSync {
            val parent = FrameLayout(context)
            val view = PamImageView(context)
            parent.addView(view, FrameLayout.LayoutParams(100, 100))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, 200, 200)
            assertFalse(view.isLayoutRequested)
            val bitmap = Bitmap.createBitmap(37, 53, Bitmap.Config.ARGB_8888)
            view.setImageDrawable(BitmapDrawable(context.resources, bitmap))
            assertFalse(view.isLayoutRequested)
            assertFalse(parent.isLayoutRequested)
            // The drawable is still fitted to the view's frame.
            assertEquals(100, view.width)
        }
    }

    @Test
    fun hiddenImageReleasesItsBitmapAndRestoresItSilentlyWhenShown() {
        val activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val file = File(context.cacheDir, "pam-release-test.jpg")
        file.outputStream().use { out ->
            Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        val loader = NativeImageLoader(context)
        lateinit var container: FrameLayout
        lateinit var image: PamImageView
        val loaded = CountDownLatch(1)
        var loads = 0
        instrumentation.runOnMainSync {
            container = FrameLayout(activity)
            image = PamImageView(activity)
            container.addView(image, FrameLayout.LayoutParams(300, 300))
            activity.host.addView(container, FrameLayout.LayoutParams(400, 400))
            loader.load(
                NativeImageRequest(source = "file://${file.absolutePath}", fadeDurationMs = 0),
                image,
                NativeImageCallbacks(onSuccess = { loads++; loaded.countDown() }),
            )
        }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val bitmap = (image.drawable as BitmapDrawable).bitmap
            // Decoded at the cover size of the 300 px view, not 1200x900.
            assertEquals(300, bitmap.height)
            assertEquals(400, bitmap.width)
            container.visibility = View.INVISIBLE
            assertNull("A hidden image releases its pixels", image.drawable)
            container.visibility = View.VISIBLE
            assertNotNull("Shown again, the memory cache restores it in the same frame", image.drawable)
            assertEquals(1, loads)
        }
        instrumentation.runOnMainSync {
            loader.close()
            activity.finish()
        }
    }

    @Test
    fun decodePlanAtTheViewSizeStillCoversIt() {
        val plan = nativeImageDecodePlan(1080, 1350, 360, 360, IMAGE_RESIZE_AUTO, 1f)
        assertEquals(360, plan.width)
        assertEquals(450, plan.height)
    }
}
