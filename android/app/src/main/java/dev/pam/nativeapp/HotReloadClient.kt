package dev.pam.nativeapp

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class HotReloadClient(
    private val context: Context,
    private val onReload: (String) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pam-hot-reload").apply { isDaemon = true }
    }
    private var version: String? = null

    fun start() {
        executor.scheduleWithFixedDelay(::poll, 200, 300, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnection.getAndSet(null)?.disconnect()
        executor.shutdownNow()
    }

    private fun poll() {
        if (closed.get()) return
        runCatching {
            val next = request("$BASE_URL/status?version=$version", 128)
                .toString(Charsets.UTF_8)
                .trim()
            if (next.isEmpty() || next == version) return
            require(next.matches(Regex("[a-f0-9]{16,64}"))) { "Invalid hot reload version" }
            if (version == null) {
                version = next
                return
            }
            val bundle = request("$BASE_URL/bundle?version=$next", MAX_BUNDLE_BYTES)
            val destination = context.filesDir.resolve("pam/dev/$next")
            val entry = DevBundle.extract(bundle, destination)
            version = next
            onReload(entry.absolutePath)
            cleanupExcept(next)
        }.onFailure {
            if (it !is HotReloadTransportException) {
                onError(it.message ?: "Hot reload failed")
            }
        }
    }

    private fun request(url: String, limit: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        activeConnection.set(connection)
        if (closed.get()) {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
            error("Hot reload is closed")
        }
        connection.connectTimeout = 250
        connection.readTimeout = 1_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Hot reload server returned ${connection.responseCode}" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= limit) { "Hot reload response is too large" }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        } catch (error: IOException) {
            throw HotReloadTransportException(error)
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    private class HotReloadTransportException(cause: IOException) :
        RuntimeException(cause)

    private fun cleanupExcept(active: String) {
        context.filesDir.resolve("pam/dev").listFiles()?.forEach {
            if (it.name != active) {
                it.deleteRecursively()
            }
        }
    }

    private companion object {
        const val BASE_URL = "http://127.0.0.1:39100"
        const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
    }
}
