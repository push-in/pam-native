package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** A list cell rebinding the same cached bitmap reuses its blur (no readback/blur per bind). */
@RunWith(AndroidJUnit4::class)
class PamBitmapBlurCacheInstrumentedTest {
    @Test
    fun theSameSourceAndSigmaReuseTheBlurAnotherSigmaOrSourceDoNot() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val first = PamBitmapBlur.blur(source, 8f)
        assertNotSame(source, first)
        assertSame(first, PamBitmapBlur.blur(source, 8f))
        assertSame(first, PamBitmapBlur.blur(source, 8.02f))
        val wider = PamBitmapBlur.blur(source, 16f)
        assertNotSame(first, wider)
        val other = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        assertNotSame(wider, PamBitmapBlur.blur(other, 16f))
    }
}
