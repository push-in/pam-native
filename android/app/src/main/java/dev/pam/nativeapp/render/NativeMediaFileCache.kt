package dev.pam.nativeapp.render

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class MediaCacheRequest(
    val source: String,
    val policy: Int,
    val key: String?,
    val maxAgeMs: Long,
    val maxBytes: Long,
    val checksum: String?,
    val pinOffline: Boolean,
    val streaming: Boolean,
    val downloadWhilePlaying: Boolean,
)

internal data class MediaCacheCallbacks(
    val hit: (String) -> Unit = {},
    val miss: (String) -> Unit = {},
    val progress: (String, Long, Long) -> Unit = { _, _, _ -> },
    val ready: (String, Long) -> Unit = { _, _ -> },
    val error: (String) -> Unit = {},
)

internal class NativeMediaFileCache(context: Context) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.cacheDir, "pam-media-v1")
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "pam-media-cache").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File>>()
    private val closed = AtomicBoolean()

    fun resolve(
        request: MediaCacheRequest,
        callbacks: MediaCacheCallbacks,
        completion: (Uri) -> Unit,
    ) {
        val uri = runCatching { Uri.parse(request.source) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https")) {
            completion(uri ?: Uri.EMPTY)
            return
        }
        if (request.policy == MEDIA_CACHE_NONE || request.policy == MEDIA_CACHE_MEMORY) {
            completion(uri)
            return
        }
        val identity = request.key ?: sha256(request.source.toByteArray())
        val fileKey = sha256(identity.toByteArray())
        val target = File(root, "$fileKey.media")
        val fresh = target.isFile &&
            target.length() in 1..effectiveMax(request) &&
            (request.maxAgeMs <= 0 || System.currentTimeMillis() - target.lastModified() <= request.maxAgeMs)
        if (fresh && request.policy != MEDIA_CACHE_NETWORK_FIRST) {
            target.setLastModified(System.currentTimeMillis())
            callbacks.hit(identity)
            completion(Uri.fromFile(target))
            return
        }
        val staleAvailable = target.isFile && target.length() in 1..effectiveMax(request)
        val servedStale =
            request.policy == MEDIA_CACHE_STALE_WHILE_REVALIDATE && staleAvailable
        if (servedStale) {
            target.setLastModified(System.currentTimeMillis())
            callbacks.hit(identity)
            completion(Uri.fromFile(target))
        }
        callbacks.miss(identity)
        if (request.policy == MEDIA_CACHE_CACHE_ONLY) {
            callbacks.error("Media is not available in the local cache.")
            return
        }
        if (request.streaming || request.downloadWhilePlaying) {
            completion(uri)
        }
        val future = inFlight.computeIfAbsent(identity) {
            CompletableFuture.supplyAsync(
                { download(request, identity, fileKey, target, callbacks) },
                executor,
            ).whenComplete { _, _ -> inFlight.remove(identity) }
        }
        future.whenComplete { file, error ->
            main.post {
                if (error != null || file == null) {
                    if (request.policy == MEDIA_CACHE_NETWORK_FIRST && staleAvailable) {
                        target.setLastModified(System.currentTimeMillis())
                        callbacks.hit(identity)
                        completion(Uri.fromFile(target))
                    } else if (!servedStale) {
                        callbacks.error(error?.cause?.message ?: error?.message ?: "Media cache failed.")
                    }
                } else {
                    callbacks.ready(identity, file.length())
                    if (!servedStale && !request.streaming && !request.downloadWhilePlaying) {
                        completion(Uri.fromFile(file))
                    }
                }
            }
        }
    }

    private fun download(
        request: MediaCacheRequest,
        identity: String,
        fileKey: String,
        target: File,
        callbacks: MediaCacheCallbacks,
    ): File {
        check(!closed.get()) { "Media cache is closed." }
        root.mkdirs()
        val temporary = File(root, "$fileKey.${System.nanoTime()}.pending")
        val connection = URL(request.source).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "video/*,audio/*,application/octet-stream")
        try {
            require(connection.responseCode in 200..299) {
                "Media request failed with HTTP ${connection.responseCode}."
            }
            val expected = connection.contentLengthLong
            val maximum = effectiveMax(request)
            require(expected in -1..maximum) { "Media exceeds its cache size limit." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maximum) { "Media exceeds its cache size limit." }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        if (total % (256 * 1024) < read) {
                            main.post { callbacks.progress(identity, total, expected.coerceAtLeast(0)) }
                        }
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            require(request.checksum == null || checksum == request.checksum) {
                "Media checksum verification failed."
            }
            check(temporary.renameTo(target)) { "Cannot activate cached media." }
            if (request.pinOffline) File(root, "$fileKey.pin").writeText("1")
            trim(request.maxBytes.takeIf { it > 0 } ?: DEFAULT_DISK_BYTES)
            return target
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun trim(limit: Long) {
        val files = root.listFiles()
            ?.filter { it.extension == "media" }
            ?.sortedByDescending(File::lastModified)
            ?: return
        var size = 0L
        files.forEach { file ->
            size += file.length()
            val pinned = File(root, "${file.nameWithoutExtension}.pin").isFile
            if (size > limit.coerceIn(MIN_DISK_BYTES, MAX_DISK_BYTES) && !pinned) {
                file.delete()
            }
        }
    }

    private fun effectiveMax(request: MediaCacheRequest): Long =
        (request.maxBytes.takeIf { it > 0 } ?: MAX_FILE_BYTES)
            .coerceIn(1, MAX_FILE_BYTES)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
        executor.shutdownNow()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        const val DEFAULT_DISK_BYTES = 512L * 1024 * 1024
        const val MIN_DISK_BYTES = 16L * 1024 * 1024
        const val MAX_DISK_BYTES = 4L * 1024 * 1024 * 1024
    }
}
