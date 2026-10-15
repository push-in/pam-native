package dev.pam.nativeapp.modules

import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Bounded disk cache of media-library thumbnails (JPEG). Least recently used
 * files go first once the folder passes [maxBytes] or [maxFiles]; a hit only
 * touches the file's modification time.
 */
internal class MediaThumbnails(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
) {
    fun file(key: String): File = File(directory, "${digest(key)}.jpg")

    fun hit(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        file.setLastModified(System.currentTimeMillis())
        return true
    }

    fun store(file: File, bitmap: Bitmap) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.${Thread.currentThread().id}.tmp")
        try {
            temporary.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "Cannot encode the thumbnail"
                }
            }
            check(temporary.renameTo(file) || (file.isFile && temporary.delete())) { "Cannot store the thumbnail" }
        } finally {
            temporary.delete()
        }
        trim()
    }

    /** Drops the oldest thumbnails until the cache is back at 75% of its limits. */
    fun trim() {
        val files = directory.listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".jpg") }
            ?: return
        val victims = victims(files.map { Entry(it.name, it.length(), it.lastModified()) }, maxBytes, maxFiles)
        if (victims.isEmpty()) return
        val names = victims.toHashSet()
        files.filter { it.name in names }.forEach(File::delete)
    }

    internal data class Entry(val name: String, val bytes: Long, val lastUsed: Long)

    companion object {
        const val MAX_BYTES = 24L * 1024 * 1024
        const val MAX_FILES = 600
        const val MIN_EDGE = 32
        const val MAX_EDGE = 1024
        private const val JPEG_QUALITY = 82

        fun clampEdge(value: Long): Int = value.coerceIn(MIN_EDGE.toLong(), MAX_EDGE.toLong()).toInt()

        /** Names to delete, oldest first, so the rest fits 75% of both limits. */
        internal fun victims(entries: List<Entry>, maxBytes: Long, maxFiles: Int): List<String> {
            var bytes = entries.sumOf(Entry::bytes)
            var count = entries.size
            if (bytes <= maxBytes && count <= maxFiles) return emptyList()
            val targetBytes = maxBytes * 3 / 4
            val targetFiles = maxFiles * 3 / 4
            val out = ArrayList<String>()
            for (entry in entries.sortedBy(Entry::lastUsed)) {
                if (bytes <= targetBytes && count <= targetFiles) break
                out += entry.name
                bytes -= entry.bytes
                count--
            }
            return out
        }

        /** Largest power-of-two sample size that keeps the longer edge >= [maxPx]. */
        fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
            val edge = maxOf(width, height)
            if (edge <= 0) return 1
            var sample = 1
            while (edge / (sample * 2) >= maxPx) sample *= 2
            return sample
        }

        /** Scales [bitmap] down so its longer edge is at most [maxPx]; never up. */
        fun fit(bitmap: Bitmap, maxPx: Int): Bitmap {
            val (width, height) = fittedSize(bitmap.width, bitmap.height, maxPx)
            if (width == bitmap.width && height == bitmap.height) return bitmap
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
        }

        fun fittedSize(width: Int, height: Int, maxPx: Int): Pair<Int, Int> {
            val edge = maxOf(width, height)
            if (edge <= maxPx || edge <= 0) return width to height
            val scale = maxPx.toDouble() / edge
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }

        private fun digest(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it) }
    }
}
