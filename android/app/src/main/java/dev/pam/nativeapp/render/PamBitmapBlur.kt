package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.widget.ImageView
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * React Native style `blurRadius` for images: a three-pass box blur
 * (≈ Gaussian, σ in px) on a downscaled copy. Opaque images keep clamped
 * edges so they stay opaque to their bounds; an image with a transparent
 * border (an icon, a glyph used as a shadow) fades into transparency
 * instead, so its blur never becomes a filled rectangle. Works on every API
 * level and costs nothing per frame once the image is displayed.
 */
internal object PamBitmapBlur {
    /**
     * The last blur of each live source bitmap. Images draw from the shared
     * memory cache, so a list cell rebinding (a feed backdrop, a glyph shadow
     * repeated in every row) gets the same source bitmap back: reusing its
     * blur keeps a GPU readback and a box blur off the UI thread on every
     * bind while scrolling. Entries go with their source (weak keys); the
     * blurred copies are small (downscaled to σ ≈ 4).
     */
    private val blurred = java.util.WeakHashMap<Bitmap, Pair<Float, Bitmap>>()

    fun blur(source: Bitmap, sigma: Float): Bitmap {
        if (sigma <= 0f || source.width <= 0 || source.height <= 0) return source
        synchronized(blurred) {
            blurred[source]?.takeIf { (applied, bitmap) -> abs(applied - sigma) < 0.05f && !bitmap.isRecycled }
                ?.let { return it.second }
        }
        val output = blurUncached(source, sigma)
        if (output !== source) synchronized(blurred) { blurred[source] = sigma to output }
        return output
    }

    private fun blurUncached(source: Bitmap, sigma: Float): Bitmap {
        val readable = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return source
        } else {
            source
        }
        val scale = min(1f, 4f / sigma).coerceAtLeast(1f / 64f)
        val width = max(1, (readable.width * scale).roundToInt())
        val height = max(1, (readable.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(readable, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        blurPixels(pixels, width, height, sigma * scale)
        val output = if (small !== readable && small.isMutable) small else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * σ authored in view pixels → σ in the bitmap's own pixels, through the
     * scale the image is drawn at. A 96 px icon shown at 92 dp on a 3× screen
     * is drawn ~2.9× larger, so blurring its bitmap by the screen σ blurred it
     * ~2.9× too much (a glyph shadow turned into a uniform translucent square).
     */
    fun bitmapSigma(
        viewSigma: Float,
        viewWidth: Int,
        viewHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        scaleType: ImageView.ScaleType?,
    ): Float {
        if (viewSigma <= 0f || viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return viewSigma
        val sx = viewWidth.toFloat() / bitmapWidth
        val sy = viewHeight.toFloat() / bitmapHeight
        val drawScale = when (scaleType) {
            ImageView.ScaleType.CENTER_CROP -> max(sx, sy)
            ImageView.ScaleType.FIT_XY -> sqrt(sx * sy)
            ImageView.ScaleType.CENTER, ImageView.ScaleType.MATRIX -> 1f
            ImageView.ScaleType.CENTER_INSIDE -> min(1f, min(sx, sy))
            else -> min(sx, sy)
        }
        return if (drawScale > 0f) viewSigma / drawScale else viewSigma
    }

    /** In-place blur of ARGB pixels with σ in those pixels. */
    fun blurPixels(pixels: IntArray, width: Int, height: Int, sigma: Float) {
        if (sigma <= 0f || width <= 0 || height <= 0) return
        // Three box passes of width w approximate a Gaussian of σ² = 3(w² - 1)/12.
        val boxWidth = sqrt(4f * sigma * sigma + 1f)
        val radius = max(1, ceil((boxWidth - 1f) / 2f).toInt())
        val transparentEdges = hasTranslucentBorder(pixels, width, height)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            horizontal(pixels, scratch, width, height, radius, transparentEdges)
            vertical(scratch, pixels, width, height, radius, transparentEdges)
        }
    }

    private fun hasTranslucentBorder(pixels: IntArray, width: Int, height: Int): Boolean {
        for (x in 0 until width) {
            if (pixels[x] ushr 24 < 255 || pixels[(height - 1) * width + x] ushr 24 < 255) return true
        }
        for (y in 0 until height) {
            if (pixels[y * width] ushr 24 < 255 || pixels[y * width + width - 1] ushr 24 < 255) return true
        }
        return false
    }

    private fun horizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, transparentEdges: Boolean) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            fun at(x: Int): Int = if (transparentEdges && (x < 0 || x >= width)) 0 else input[row + x.coerceIn(0, width - 1)]
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = at(offset)
                a += pixel ushr 24; r += pixel shr 16 and 0xFF; g += pixel shr 8 and 0xFF; b += pixel and 0xFF
            }
            for (x in 0 until width) {
                output[row + x] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outgoing = at(x - radius)
                val incoming = at(x + radius + 1)
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
                g += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }

    private fun vertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, transparentEdges: Boolean) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            fun at(y: Int): Int = if (transparentEdges && (y < 0 || y >= height)) 0 else input[y.coerceIn(0, height - 1) * width + x]
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = at(offset)
                a += pixel ushr 24; r += pixel shr 16 and 0xFF; g += pixel shr 8 and 0xFF; b += pixel and 0xFF
            }
            for (y in 0 until height) {
                output[y * width + x] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outgoing = at(y - radius)
                val incoming = at(y + radius + 1)
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
                g += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }
}
