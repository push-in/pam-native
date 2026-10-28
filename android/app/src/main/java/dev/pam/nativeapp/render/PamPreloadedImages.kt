package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Packaged images decoded ahead of rendering by `Image::prefetch()` with
 * `asset://` sources (a screen background, a splash texture). Each keeps its
 * full resolution once per process, shared by every renderer, so an Image
 * with that exact source paints in the frame that binds it: no decode, no
 * cache-key size match, no fade. A theme switch between two preloaded
 * sources only swaps the bitmap. Bounded by [MAX_ENTRIES] and [MAX_BYTES];
 * a critical memory trim drops them (the next bind decodes normally).
 */
internal object PamPreloadedImages {
    const val MAX_ENTRIES = 8
    const val MAX_BYTES = 48L * 1024 * 1024

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    fun get(source: String): Bitmap? = bitmaps[source]

    /** Worker thread. Returns the decoded byte count (0 when already loaded). */
    fun preload(context: Context, source: String): Long {
        if (bitmaps.containsKey(source)) return 0
        val path = requireNotNull(normalizedPamAssetPath(source)) { "Only packaged asset:// images preload." }
        val bytes = context.assets.open(path).use { it.readBytes() }
        val bitmap = decode(bytes)
        val size = bitmap.allocationByteCount.toLong()
        synchronized(this) {
            bitmaps[source]?.let { return 0 }
            require(bitmaps.size < MAX_ENTRIES) { "At most $MAX_ENTRIES preloaded images." }
            require(bitmaps.values.sumOf { it.allocationByteCount.toLong() } + size <= MAX_BYTES) {
                "Preloaded images exceed ${MAX_BYTES / 1024 / 1024} MiB."
            }
            bitmaps[source] = bitmap
        }
        return size
    }

    fun clear() = bitmaps.clear()

    private fun decode(bytes: ByteArray): Bitmap {
        // API 28+: a GPU texture (no Java heap, no upload in the first frame
        // that draws it); PamImageView copies it for a software canvas.
        val hardware = Build.VERSION.SDK_INT >= 28
        fun options(config: Bitmap.Config) = BitmapFactory.Options().apply {
            inPreferredConfig = config
            inScaled = false
        }
        val bitmap = requireNotNull(
            runCatching {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    options(if (hardware) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888),
                )
            }.getOrNull()
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(Bitmap.Config.ARGB_8888)),
        ) { "Unsupported image format." }
        if (bitmap.config != Bitmap.Config.HARDWARE) bitmap.prepareToDraw()
        return bitmap
    }
}
