package dev.pam.nativeapp.render

import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamBitmapBlurTest {
    @Test
    fun sigmaFollowsTheScaleTheBitmapIsDrawnAt() {
        // A 96 px icon contained in a 276 px view (92 dp at 3×) is drawn 2.875× larger.
        val sigma = PamBitmapBlur.bitmapSigma(24.25f, 276, 276, 96, 96, ImageView.ScaleType.FIT_CENTER)
        assertEquals(8.43f, sigma, 0.01f)
        // Cover uses the larger axis scale, center draws 1:1.
        assertEquals(5f, PamBitmapBlur.bitmapSigma(10f, 200, 100, 100, 100, ImageView.ScaleType.CENTER_CROP), 0.001f)
        assertEquals(10f, PamBitmapBlur.bitmapSigma(10f, 200, 100, 100, 100, ImageView.ScaleType.CENTER), 0.001f)
        // Not laid out: unchanged.
        assertEquals(10f, PamBitmapBlur.bitmapSigma(10f, 0, 0, 100, 100, ImageView.ScaleType.FIT_CENTER), 0.001f)
    }

    @Test
    fun aGlyphOnATransparentCanvasFadesInsteadOfFillingTheBox() {
        val size = 48
        val pixels = IntArray(size * size)
        for (y in 18 until 30) for (x in 18 until 30) pixels[y * size + x] = 0xFF000000.toInt()
        PamBitmapBlur.blurPixels(pixels, size, size, 4f)
        val corner = pixels[0] ushr 24
        val center = pixels[24 * size + 24] ushr 24
        assertEquals("corners of an icon's blur stay transparent", 0, corner)
        assertTrue("the glyph keeps its shadow: $center", center > 120)
    }

    @Test
    fun anOpaqueImageStaysOpaqueToItsBounds() {
        val size = 16
        val pixels = IntArray(size * size) { i -> if (i % size < 8) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        PamBitmapBlur.blurPixels(pixels, size, size, 3f)
        assertTrue(pixels.all { it ushr 24 == 255 })
    }
}
