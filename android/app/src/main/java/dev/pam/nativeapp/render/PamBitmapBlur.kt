package dev.pam.nativeapp.render

import android.graphics.Bitmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * React Native style `blurRadius` for images: a three-pass box blur
 * (≈ Gaussian, σ in px) on a downscaled copy, with clamped edges so the
 * blurred image stays opaque to its bounds. Works on every API level and
 * costs nothing per frame once the image is displayed.
 */
internal object PamBitmapBlur {
    fun blur(source: Bitmap, sigma: Float): Bitmap {
        if (sigma <= 0f || source.width <= 0 || source.height <= 0) return source
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
        val scaledSigma = sigma * scale
        // Three box passes of width w approximate a Gaussian of σ² = 3(w² - 1)/12.
        val boxWidth = sqrt(4f * scaledSigma * scaledSigma + 1f)
        val radius = max(1, ceil((boxWidth - 1f) / 2f).toInt())
        val scratch = IntArray(pixels.size)
        repeat(3) {
            horizontal(pixels, scratch, width, height, radius)
            vertical(scratch, pixels, width, height, radius)
        }
        val output = if (small !== readable && small.isMutable) small else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun horizontal(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = input[row + offset.coerceIn(0, width - 1)]
                a += pixel ushr 24; r += pixel shr 16 and 0xFF; g += pixel shr 8 and 0xFF; b += pixel and 0xFF
            }
            for (x in 0 until width) {
                output[row + x] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outgoing = input[row + (x - radius).coerceIn(0, width - 1)]
                val incoming = input[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
                g += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }

    private fun vertical(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -radius..radius) {
                val pixel = input[offset.coerceIn(0, height - 1) * width + x]
                a += pixel ushr 24; r += pixel shr 16 and 0xFF; g += pixel shr 8 and 0xFF; b += pixel and 0xFF
            }
            for (y in 0 until height) {
                output[y * width + x] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val outgoing = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val incoming = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += (incoming shr 16 and 0xFF) - (outgoing shr 16 and 0xFF)
                g += (incoming shr 8 and 0xFF) - (outgoing shr 8 and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }
}
