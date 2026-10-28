package dev.pam.nativeapp

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only client of the `pam dev` sync server (reached through
 * `adb reverse tcp:39100`, over USB or Wi-Fi).
 *
 * It long-polls `/status`, fetches the manifest (a delta after the first
 * sync), downloads only the files the device does not hold and hands the
 * new version to the runtime. Transport failures (cable unplugged, adb
 * restarted, Wi-Fi reconnecting) back off quietly and resume on their own.
 */
internal class HotReloadClient(
    private val context: Context,
    /** Entry the runtime currently runs; decides whether `live/` is in use. */
    initialEntryPath: String?,
    private val onReload: (HotReloadReceipt) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pam-hot-reload").apply { isDaemon = true }
    }
    /** Reports must not wait behind the long poll on [executor]. */
    private val reporter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-hot-reload-report").apply { isDaemon = true }
    }
    private val store: HotReloadStore = run {
        val root = context.filesDir.resolve("pam/dev")
        val bootDirectory = initialEntryPath?.let(::File)?.parentFile
            ?.takeUnless { it.absolutePath == File(root, "live").absolutePath }
        HotReloadStore(
            root,
            listOfNotNull(
                bootDirectory?.let(HotReloadStore::directorySource),
                HotReloadLocalSource { path -> runCatching { context.assets.open("pam/$path") }.getOrNull() },
            ),
        )
    }

    @Volatile
    private var runningLive = initialEntryPath == store.liveEntry.absolutePath
    private var failures = 0
    private var transportLogged = false
    private val reported = AtomicReference<HotReloadReport?>()

    fun start() {
        executor.schedule(::sync, 200, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnection.getAndSet(null)?.disconnect()
        executor.shutdownNow()
        reporter.shutdownNow()
    }

    /** The runtime committed (or failed) the first frame of the last reload. */
    fun reportReloaded(durationNanos: Long, failed: Boolean) {
        val report = reported.getAndSet(null) ?: return
        if (closed.get()) return
        val milliseconds = TimeUnit.NANOSECONDS.toMillis(durationNanos)
        Log.i(
            TAG,
            "Reload ${report.version.take(12)} ${if (failed) "failed" else "rendered"} in $milliseconds ms " +
                "(${report.files} files, ${report.bytes} bytes applied)",
        )
        val outcome = if (failed) OUTCOME_FAILED else OUTCOME_SUCCEEDED
        runCatching {
            reporter.execute {
                runCatching {
                    request(
                        "$BASE_URL/reloaded?version=${report.version}&outcome=$outcome&ms=$milliseconds" +
                            "&bytes=${report.bytes}&files=${report.files}",
                        method = "POST",
                        body = ByteArray(0),
                        readTimeoutMillis = 2_000,
                        tracked = false,
                    ) { it.readLimited(1_024) }
                }
            }
        }
    }

    private fun currentVersion(): String? =
        if (runningLive) store.liveManifest?.version else null

    private fun sync() {
        if (closed.get()) return
        if (store.hasPendingRequest) {
            schedule(PENDING_POLL_MILLIS)
            return
        }
        val delay = try {
            syncOnce()
            0L
        } catch (stale: HotReloadVersionChanged) {
            0L
        } catch (error: HotReloadTransportException) {
            failures++
            if (!transportLogged) {
                Log.i(TAG, "Waiting for `pam dev` on 127.0.0.1:39100 (adb reverse): ${error.cause?.message}")
                transportLogged = true
            }
            backoff()
        } catch (error: Exception) {
            if (closed.get()) return
            failures++
            Log.w(TAG, "Hot reload failed", error)
            onError(error.message ?: "Hot reload failed")
            backoff()
        }
        schedule(delay)
    }

    private fun backoff(): Long = backoffDelayMillis(failures)

    private fun schedule(delayMillis: Long) {
        if (closed.get()) return
        runCatching { executor.schedule(::sync, delayMillis, TimeUnit.MILLISECONDS) }
    }

    private fun syncOnce() {
        val known = currentVersion()
        val base = store.liveManifest
        // While live/ runs `known`, a new version arrives with its delta and
        // edited files in the same long-poll response (one round trip).
        val deltaCapable = known != null && base?.version == known
        var confirmedAtNanos = 0L
        var started = 0L
        var downloaded = 0L
        val status = request(
            "$BASE_URL/status?version=${known.orEmpty()}&wait=1${if (deltaCapable) "&delta=1" else ""}",
            readTimeoutMillis = LONG_POLL_TIMEOUT_MILLIS,
        ) { input ->
            val buffered = input.buffered(64 * 1024)
            val head = buffered.readLine(MAX_STATUS_LINE_BYTES)
            confirmedAtNanos = System.nanoTime()
            started = SystemClock.elapsedRealtime()
            if (!head.startsWith(SYNC_MAGIC)) {
                return@request HotReloadStatus(head.trim(), null)
            }
            val fields = head.removePrefix(SYNC_MAGIC).trim().split(' ')
            require(fields.size == 2 && deltaCapable && base != null) { "Invalid hot reload sync response" }
            val next = fields[0]
            val length = fields[1].toIntOrNull()
            require(HotReloadManifest.isVersion(next) && length != null && length in 0..MAX_MANIFEST_BYTES) {
                "Invalid hot reload sync response"
            }
            val delta = ByteArray(length).also { java.io.DataInputStream(buffered).readFully(it) }
                .toString(Charsets.UTF_8)
            val manifest = HotReloadManifest.parse(delta, next, base)
            val added = delta.lineSequence().filter { it.startsWith("+ ") }.map { it.substring(2, 66) }.toSet()
            downloaded = store.receive(buffered, added)
            HotReloadStatus(next, manifest)
        }
        if (transportLogged) {
            Log.i(TAG, "Connected to `pam dev`")
            transportLogged = false
        }
        failures = 0
        val next = status.version
        if (next.isEmpty() || next == known) return
        require(HotReloadManifest.isVersion(next)) { "Invalid hot reload version" }
        val manifest = status.manifest ?: HotReloadManifest.parse(
            request(
                "$BASE_URL/manifest?version=$next${base?.let { "&base=${it.version}" }.orEmpty()}",
            ) { it.readLimited(MAX_MANIFEST_BYTES) }.toString(Charsets.UTF_8),
            next,
            base,
        )
        val plan = store.plan(manifest)
        if (plan.missing.isNotEmpty()) {
            val body = plan.missing.joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
            downloaded += request("$BASE_URL/objects", method = "POST", body = body) { input ->
                store.receive(input, plan.missing)
            }
        }
        if (plan.isEmpty && runningLive) return
        val files = plan.changed.size + plan.removed.size
        Log.i(
            TAG,
            "Version ${next.take(12)}: $files files changed, ${plan.missing.size} downloaded " +
                "($downloaded bytes), prepared in ${SystemClock.elapsedRealtime() - started} ms",
        )
        store.begin(plan)
        val inPlace = runningLive
        if (!inPlace) {
            // Nothing runs from live/ yet: write it now, then switch to it.
            try {
                store.apply(plan)
            } catch (error: Exception) {
                store.abandon(next)
                throw error
            }
        }
        reported.set(HotReloadReport(next, files, plan.changedBytes))
        runningLive = true
        onReload(
            HotReloadReceipt(
                entryPath = store.liveEntry.absolutePath,
                confirmedAtNanos = confirmedAtNanos,
                transferredBytes = downloaded,
                beforeNextRequest = if (inPlace) ({ store.apply(plan) }) else null,
                previousRequestReleased = {
                    if (!closed.get()) {
                        runCatching { executor.execute { store.previousRequestReleased(next) } }
                    }
                },
            ),
        )
    }

    private fun <T> request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
        tracked: Boolean = true,
        read: (InputStream) -> T,
    ): T {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: IOException) {
            throw HotReloadTransportException(error)
        }
        if (tracked) activeConnection.set(connection)
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            throw HotReloadTransportException(IOException("Hot reload is closed"))
        }
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.requestMethod = method
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_CONFLICT) {
                // pam dev published a newer version meanwhile: start over.
                throw HotReloadVersionChanged()
            }
            require(status in 200..299) { "Hot reload server returned $status" }
            return connection.inputStream.use(read)
        } catch (error: IOException) {
            throw HotReloadTransportException(error)
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private fun InputStream.readLine(limit: Int): String {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = read()
            if (value < 0 || value == '\n'.code) break
            require(output.size() < limit) { "Hot reload status is too large" }
            output.write(value)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) { "Hot reload response is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class HotReloadTransportException(cause: IOException) : RuntimeException(cause)

    private class HotReloadVersionChanged : RuntimeException("Hot reload version changed")

    private class HotReloadStatus(val version: String, val manifest: HotReloadManifest?)

    private data class HotReloadReport(val version: String, val files: Int, val bytes: Long)

    internal companion object {
        const val TAG = "PamNativeHotReload"
        const val BASE_URL = "http://127.0.0.1:39100"
        const val CONNECT_TIMEOUT_MILLIS = 500
        const val READ_TIMEOUT_MILLIS = 10_000
        /** The server answers a long poll within 20 s. */
        const val LONG_POLL_TIMEOUT_MILLIS = 25_000
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_STATUS_LINE_BYTES = 256
        const val SYNC_MAGIC = "PNS1 "
        const val PENDING_POLL_MILLIS = 50L
        const val RETRY_MILLIS = 300L
        const val QUICK_BACKOFF_MILLIS = 2_000L
        const val QUICK_RETRIES = 6
        const val MAX_BACKOFF_MILLIS = 30_000L

        /**
         * Retry delay after [failures] consecutive failed syncs: a quick ramp to
         * 2 s covers a dev server restart (about 10 s), then the delay doubles to
         * a 30 s ceiling, so a debug build with no server stops waking the radio
         * and the CPU every 2 s for the life of the app.
         */
        fun backoffDelayMillis(failures: Int): Long {
            val count = maxOf(failures, 1)
            if (count <= QUICK_RETRIES) {
                return minOf(QUICK_BACKOFF_MILLIS, RETRY_MILLIS shl minOf(count - 1, 3))
            }
            return minOf(MAX_BACKOFF_MILLIS, QUICK_BACKOFF_MILLIS shl minOf(count - QUICK_RETRIES, 5))
        }
        const val OUTCOME_SUCCEEDED = 1
        const val OUTCOME_FAILED = 2
    }
}

internal data class HotReloadReceipt(
    val entryPath: String,
    val confirmedAtNanos: Long,
    val transferredBytes: Long,
    /** Runs on the PHP worker after the previous request shut down, before the next starts. */
    val beforeNextRequest: (() -> Unit)?,
    val previousRequestReleased: () -> Unit,
)
