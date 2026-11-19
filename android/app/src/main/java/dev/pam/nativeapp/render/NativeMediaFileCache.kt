package dev.pam.nativeapp.render

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
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

/**
 * One player's interest in a [NativeMediaFileCache.resolve]. Cancelling it
 * (the player released, its source changed, the view detached) drops a
 * cache copy that has not started yet and stops a download nobody else
 * waits for. Main thread.
 */
internal fun interface MediaCacheHandle {
    fun cancel()

    companion object {
        val NONE = MediaCacheHandle {}
    }
}

internal class NativeMediaFileCache(
    context: Context,
    /** How long a streaming player gets the bandwidth before the cache copy starts. */
    private val streamedDownloadDelayMs: Long = STREAMED_DOWNLOAD_DELAY_MS,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val root = File(context.cacheDir, "pam-media-v1")
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "pam-media-cache").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, Download>()
    private val closed = AtomicBoolean()

    /** A download shared by every resolve of the same identity. */
    private class Download(val identity: String) {
        val cancelled = AtomicBoolean()
        /** Live subscriptions (main thread). */
        var subscribers = 0
        lateinit var future: CompletableFuture<File>
    }

    private inner class Subscription : MediaCacheHandle {
        var cancelled = false
        var pending: Runnable? = null
        var download: Download? = null

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            pending?.let(main::removeCallbacks)
            pending = null
            download?.let(::unsubscribe)
            download = null
        }
    }

    /** Number of downloads running or queued (tests, diagnostics). */
    internal val downloadsInFlight: Int get() = inFlight.size

    /** Main thread. Cancel the returned handle when the player no longer needs the source. */
    fun resolve(
        request: MediaCacheRequest,
        callbacks: MediaCacheCallbacks,
        completion: (Uri) -> Unit,
    ): MediaCacheHandle {
        val uri = runCatching { Uri.parse(request.source) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https")) {
            completion(uri ?: Uri.EMPTY)
            return MediaCacheHandle.NONE
        }
        if (request.policy == MEDIA_CACHE_NONE || request.policy == MEDIA_CACHE_MEMORY) {
            completion(uri)
            return MediaCacheHandle.NONE
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
            return MediaCacheHandle.NONE
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
            return MediaCacheHandle.NONE
        }
        val subscription = Subscription()
        val streamed = request.streaming || request.downloadWhilePlaying
        if (streamed) {
            completion(uri)
            // The player streams the start now: the copy for the cache waits
            // until it buffered, or both requests split the bandwidth and the
            // first frame took twice as long on a mobile network. A player
            // released before then (viewer closed) never starts it.
            if (!inFlight.containsKey(identity)) {
                val pending = Runnable {
                    subscription.pending = null
                    if (!closed.get() && !subscription.cancelled) {
                        startDownload(subscription, request, identity, fileKey, target, callbacks, staleAvailable, servedStale, completion, streamed)
                    }
                }
                subscription.pending = pending
                main.postDelayed(pending, streamedDownloadDelayMs)
                return subscription
            }
        }
        startDownload(subscription, request, identity, fileKey, target, callbacks, staleAvailable, servedStale, completion, streamed)
        return subscription
    }

    private fun startDownload(
        subscription: Subscription,
        request: MediaCacheRequest,
        identity: String,
        fileKey: String,
        target: File,
        callbacks: MediaCacheCallbacks,
        staleAvailable: Boolean,
        servedStale: Boolean,
        completion: (Uri) -> Unit,
        streamed: Boolean,
    ) {
        if (streamed && target.isFile && target.length() in 1..effectiveMax(request)) {
            // Another request cached it meanwhile.
            callbacks.ready(identity, target.length())
            return
        }
        val download = inFlight.computeIfAbsent(identity) {
            Download(identity).also { created ->
                created.future = CompletableFuture.supplyAsync(
                    { download(created, request, identity, fileKey, target, callbacks) },
                    executor,
                ).whenComplete { _, _ -> inFlight.remove(identity, created) }
            }
        }
        download.subscribers++
        subscription.download = download
        download.future.whenComplete { file, error ->
            main.post {
                if (subscription.cancelled) return@post
                subscription.download = null
                download.subscribers--
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
                    if (!servedStale && !streamed) {
                        completion(Uri.fromFile(file))
                    }
                }
            }
        }
    }

    /**
     * Downloads with the image loader's resilience: a 429/503 pauses the host
     * (honoring Retry-After) and transient failures retry with backoff a few
     * times; 4xx and size/checksum failures fail at once.
     */
    private fun download(
        download: Download,
        request: MediaCacheRequest,
        identity: String,
        fileKey: String,
        target: File,
        callbacks: MediaCacheCallbacks,
    ): File {
        var attempt = 0
        while (true) {
            try {
                return downloadOnce(download, request, identity, fileKey, target, callbacks)
            } catch (error: Throwable) {
                if (closed.get() || download.cancelled.get() || attempt >= DOWNLOAD_RETRIES) throw error
                val delay = mediaRetryDelayMs(error, attempt, kotlin.random.Random.nextDouble()) ?: throw error
                attempt++
                Thread.sleep(delay)
            }
        }
    }

    /** The last subscriber left: the download stops at its next buffer (main thread). */
    private fun unsubscribe(download: Download) {
        download.subscribers--
        if (download.subscribers > 0) return
        download.cancelled.set(true)
        inFlight.remove(download.identity, download)
        download.future.cancel(false)
    }

    private fun downloadOnce(
        download: Download,
        request: MediaCacheRequest,
        identity: String,
        fileKey: String,
        target: File,
        callbacks: MediaCacheCallbacks,
    ): File {
        check(!closed.get()) { "Media cache is closed." }
        if (download.cancelled.get()) throw CancellationException("Media download cancelled.")
        val host = URL(request.source).host
        PamMediaHostGate.remainingMs(host).takeIf { it > 0 }?.let { throw MediaHostPausedException(host, it) }
        root.mkdirs()
        val temporary = File(root, "$fileKey.${System.nanoTime()}.pending")
        val connection = URL(request.source).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "video/*,audio/*,application/octet-stream")
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                val retryAfter = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                if (status in MEDIA_HOST_PAUSE_STATUS) PamMediaHostGate.pause(connection.url.host, retryAfter)
                throw MediaHttpException(status, retryAfter)
            }
            PamMediaHostGate.succeeded(connection.url.host)
            val expected = connection.contentLengthLong
            val maximum = effectiveMax(request)
            require(expected in -1..maximum) { "Media exceeds its cache size limit." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (download.cancelled.get() || closed.get()) {
                            throw CancellationException("Media download cancelled.")
                        }
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
            val checksum = digest.digest().toHexString()
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
        val budget = limit.coerceIn(MIN_DISK_BYTES, MAX_DISK_BYTES)
        files.forEachIndexed { index, file ->
            size += file.length()
            val pinned = File(root, "${file.nameWithoutExtension}.pin").isFile
            // The newest file is the one being handed to a player: never trim it.
            if (index > 0 && size > budget && !pinned) {
                file.delete()
            }
        }
    }

    private fun effectiveMax(request: MediaCacheRequest): Long =
        (request.maxBytes.takeIf { it > 0 } ?: MAX_FILE_BYTES)
            .coerceIn(1, MAX_FILE_BYTES)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inFlight.values.forEach {
            it.cancelled.set(true)
            it.future.cancel(true)
        }
        inFlight.clear()
        executor.shutdownNow()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHexString()

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        const val STREAMED_DOWNLOAD_DELAY_MS = 4_000L
        const val DOWNLOAD_RETRIES = 4
        // `cacheMaxBytes` also bounds one file (up to 2 GB); the directory
        // keeps at most 256 MB (the RN app's media/image disk budget).
        const val DEFAULT_DISK_BYTES = 256L * 1024 * 1024
        const val MIN_DISK_BYTES = 16L * 1024 * 1024
        const val MAX_DISK_BYTES = 256L * 1024 * 1024
    }
}
