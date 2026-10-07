package dev.pam.nativeapp.render

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How a source of [sourceWidth] x [sourceHeight] pixels is decoded for a
 * view: the power-of-two [sample] the decoder subsamples by, then an exact
 * scale to [width] x [height] (the size that still covers the view). When
 * [width]/[height] equal the subsampled size, no extra scaling runs.
 */
internal data class NativeImageDecodePlan(
    val sample: Int,
    val width: Int,
    val height: Int,
    val scaled: Boolean,
)

/**
 * Plans a decode at the display size, like Fresco's ResizeOptions/Glide's
 * downsampling: [IMAGE_RESIZE_AUTO] and [IMAGE_RESIZE_RESIZE] decode a source
 * larger than the view to the smallest size that still covers it (center-crop
 * never upscales, contain draws the same pixels smaller), so a 1080x1350 feed
 * photo in a 360 px grid cell keeps 360x450 pixels instead of the 540x675 a
 * power-of-two sample alone leaves (2.25x the memory). `scale`/`none` keep
 * the source resolution (bounded by [maxPixels]); [exactScale] false (tiled
 * images, whose tile size is the source size) keeps only the subsampling.
 */
internal fun nativeImageDecodePlan(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    resizeMethod: Int,
    resizeMultiplier: Float,
    exactScale: Boolean = true,
    maxEdge: Int = 4096,
    maxPixels: Long = 33_554_432L,
): NativeImageDecodePlan {
    require(sourceWidth > 0 && sourceHeight > 0)
    val multiplier = resizeMultiplier.coerceIn(0.1f, 8f)
    val desiredWidth = max(1, (targetWidth.coerceAtMost(maxEdge) * multiplier).toInt())
    val desiredHeight = max(1, (targetHeight.coerceAtMost(maxEdge) * multiplier).toInt())
    // Cover: the larger of the two ratios keeps both edges >= the view.
    val cover = max(
        desiredWidth.toDouble() / sourceWidth,
        desiredHeight.toDouble() / sourceHeight,
    )
    val resize = when (resizeMethod) {
        IMAGE_RESIZE_RESIZE -> cover < 1.0
        IMAGE_RESIZE_SCALE, IMAGE_RESIZE_NONE -> false
        // Auto skips sources within ~10% of the view: the saving is not
        // worth a resampling pass.
        else -> cover < AUTO_RESIZE_THRESHOLD
    }
    var width = sourceWidth
    var height = sourceHeight
    if (resize) {
        width = max(1, min(sourceWidth, ceil(sourceWidth * cover - 1e-6).toInt()))
        height = max(1, min(sourceHeight, ceil(sourceHeight * cover - 1e-6).toInt()))
    }
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= width &&
        sourceHeight / (sample * 2) >= height
    ) {
        sample *= 2
    }
    // A full-resolution request must still fit the safe decode budget.
    while (width.toLong() * height > maxPixels) {
        sample *= 2
        width = max(1, sourceWidth / sample)
        height = max(1, sourceHeight / sample)
    }
    val sampledWidth = ceilDiv(sourceWidth, sample)
    val sampledHeight = ceilDiv(sourceHeight, sample)
    if (!exactScale || !resize) {
        return NativeImageDecodePlan(sample, sampledWidth, sampledHeight, scaled = false)
    }
    // Only worth an extra resampling pass when it saves at least ~10% of
    // the subsampled pixels.
    val saves = width.toLong() * height < sampledWidth.toLong() * sampledHeight * 9 / 10
    return if (saves) {
        NativeImageDecodePlan(sample, width, height, scaled = true)
    } else {
        NativeImageDecodePlan(sample, sampledWidth, sampledHeight, scaled = false)
    }
}

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

private const val AUTO_RESIZE_THRESHOLD = 0.9

/** Smallest decoded image worth a GPU-only (HARDWARE) bitmap: below it the per-buffer overhead wins. */
internal const val HARDWARE_BITMAP_MIN_PIXELS = 128L * 128L

/**
 * Bitmap storage for a decoded image.
 *
 * - API 28+ photos decode straight to `HARDWARE`: the pixels live only in
 *   GPU memory (no Java/native heap copy, no texture upload in the first
 *   frame that draws them). Never for inline glyphs (tiny, tinted, many) or
 *   images below [HARDWARE_BITMAP_MIN_PIXELS], and never when the caller
 *   reads pixels back ([allowHardware] false).
 * - Opaque JPEGs elsewhere use `RGB_565` (half the bytes) on API 26-27,
 *   where hardware bitmaps cannot be snapshotted through a Picture.
 * - Everything else keeps `ARGB_8888`.
 */
internal enum class NativeBitmapStorage { HARDWARE, RGB_565, ARGB_8888 }

internal fun nativeBitmapStorage(
    sdk: Int,
    inline: Boolean,
    allowHardware: Boolean,
    pixels: Long,
    mimeType: String?,
): NativeBitmapStorage {
    if (inline || pixels < HARDWARE_BITMAP_MIN_PIXELS) return NativeBitmapStorage.ARGB_8888
    if (allowHardware && sdk >= 28) return NativeBitmapStorage.HARDWARE
    val opaque = mimeType.equals("image/jpeg", ignoreCase = true)
    return if (opaque && sdk < 28) NativeBitmapStorage.RGB_565 else NativeBitmapStorage.ARGB_8888
}

/**
 * Decoded-image memory budget from `ActivityManager.memoryClass` (MiB), like
 * Glide's MemorySizeCalculator: an eighth of the per-app heap class (a
 * sixteenth on low-RAM devices), between 8 and 64 MiB. A 256 MiB class (Galaxy
 * S10) gets 32 MiB; a 192 MiB budget phone 24 MiB; a 512 MiB tablet 64 MiB.
 */
internal fun nativeImageMemoryCacheBytes(memoryClassMb: Int, lowRam: Boolean): Int {
    val divisor = if (lowRam) 16 else 8
    val bytes = memoryClassMb.toLong().coerceAtLeast(16) * 1024L * 1024L / divisor
    return bytes.coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024).toInt()
}
