package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import dev.pam.nativeapp.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeImageLoader : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "pam-image").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Bitmap>>()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun load(source: String, view: ImageView) {
        if (closed.get()) return
        val request = generation.incrementAndGet()
        view.setTag(IMAGE_REQUEST_TAG, request)
        cache.get(source)?.let {
            view.setImageBitmap(it)
            return
        }
        val future = runCatching {
            inFlight.computeIfAbsent(source) {
                CompletableFuture.supplyAsync(
                    {
                        fetch(source).also { bitmap ->
                            if (!closed.get()) cache.put(source, bitmap)
                        }
                    },
                    executor,
                ).whenComplete { _, _ -> inFlight.remove(source) }
            }
        }.getOrNull() ?: return
        future.whenComplete { bitmap, error ->
            if (error != null || bitmap == null) return@whenComplete
            main.post {
                if (!closed.get() && view.getTag(IMAGE_REQUEST_TAG) == request) {
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun trimMemory(critical: Boolean) {
        if (critical) {
            cache.evictAll()
        } else {
            cache.trimToSize(cache.maxSize() / 2)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        inFlight.values.forEach { future -> future.cancel(true) }
        executor.shutdownNow()
        inFlight.clear()
        cache.evictAll()
    }

    private fun fetch(source: String): Bitmap {
        val uri = URI(source)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "Remote images require HTTPS"
        }
        val connection = URL(source).openConnection() as HttpURLConnection
        connections += connection
        if (closed.get()) {
            connections -= connection
            connection.disconnect()
            error("Image loader is closed")
        }
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "image/*")
        try {
            require(connection.responseCode in 200..299) { "Image request failed" }
            val length = connection.contentLengthLong
            require(length in -1..MAX_IMAGE_BYTES) { "Image is too large" }
            val bytes = connection.inputStream.use { input ->
                val initialCapacity = when {
                    length > 0 -> length.toInt()
                    else -> DEFAULT_IMAGE_CAPACITY
                }
                val output = ByteArrayOutputStream(initialCapacity)
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_IMAGE_BYTES) { "Image is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
            var sample = 1
            val target = MAX_DECODE_EDGE
            while (
                bounds.outWidth / sample > target ||
                bounds.outHeight / sample > target ||
                bounds.outWidth.toLong() * bounds.outHeight / sample / sample > MAX_DECODE_PIXELS
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
                "Unsupported image"
            }
        } finally {
            connections -= connection
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val DEFAULT_IMAGE_CAPACITY = 64 * 1024
        const val IMAGE_REQUEST_TAG = 0x50414D49
        const val MAX_DECODE_EDGE = 4096
        const val MAX_DECODE_PIXELS = 16_777_216L
    }
}
