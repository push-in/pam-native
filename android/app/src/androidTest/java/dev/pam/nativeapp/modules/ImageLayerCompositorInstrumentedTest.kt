package dev.pam.nativeapp.modules

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageLayerCompositorInstrumentedTest {
    @Test
    fun overlayKeepsAspectPositionAndSourcePixels() {
        withOverlay(400, 200) { file ->
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val output = ImageLayerCompositor.compose(source, """[{"path":"layer.png","x":0.2,"y":0.3,"width":0.4}]""") { file }
            assertNotSame(source, output)
            assertEquals(Color.RED, output.getPixel(25, 35))
            assertEquals(Color.RED, output.getPixel(55, 45))
            assertEquals(Color.WHITE, output.getPixel(25, 55))
            assertEquals(Color.WHITE, source.getPixel(25, 35))
            assertFalse(source.isRecycled)
            source.recycle()
            output.recycle()
        }
    }

    @Test
    fun largeOverlayIsSampledAndClampedInsideCanvas() {
        withOverlay(4096, 2048) { file ->
            val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val output = ImageLayerCompositor.compose(source, """[{"path":"layer.png","x":1,"y":1,"width":0.4}]""") { file }
            assertEquals(Color.RED, output.getPixel(70, 90))
            assertEquals(Color.TRANSPARENT, output.getPixel(50, 70))
            source.recycle()
            output.recycle()
        }
    }

    @Test
    fun gifFirstFrameAndNoLayersAreSupported() {
        val file = File.createTempFile("pam-layer", ".gif", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT))
            val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            assertSame(source, ImageLayerCompositor.compose(source, "[]") { file })
            val output = ImageLayerCompositor.compose(source, """[{"path":"layer.gif","width":0.5}]""") { file }
            assertEquals(32, output.width)
            source.recycle()
            output.recycle()
        } finally { file.delete() }
    }

    private fun withOverlay(width: Int, height: Int, test: (File) -> Unit) {
        val file = File.createTempFile("pam-layer", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            test(file)
        } finally { file.delete() }
    }
}
