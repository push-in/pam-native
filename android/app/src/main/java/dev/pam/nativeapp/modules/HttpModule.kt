package dev.pam.nativeapp.modules

import dev.pam.nativeapp.BuildConfig
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class HttpModule : NativeModule, AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "pam-http").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (method != "get") {
            completion.complete(ModuleResultStatus.FAILURE, "Unknown HTTP method".toByteArray())
            return
        }
        if (closed.get()) {
            completion.complete(ModuleResultStatus.FAILURE, "HTTP module is closed".toByteArray())
            return
        }
        try {
            executor.execute {
                runCatching {
                    val values = WireMap.decode(payload)
                    val url = (values["url"] as? WireValue.Text)?.value
                        ?: error("HTTP URL is required")
                    fetch(url)
                }.fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = {
                        completion.complete(
                            ModuleResultStatus.FAILURE,
                            (it.message ?: "HTTP request failed").toByteArray(),
                        )
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            completion.complete(ModuleResultStatus.FAILURE, "HTTP module is closed".toByteArray())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        executor.shutdownNow()
    }

    private fun fetch(source: String): ByteArray {
        val uri = URI(source)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "HTTP requests require HTTPS"
        }
        require(uri.userInfo == null && uri.host != null) { "Invalid HTTP URL" }
        val connection = URL(source).openConnection() as HttpURLConnection
        connections += connection
        if (closed.get()) {
            connections -= connection
            connection.disconnect()
            error("HTTP module is closed")
        }
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        try {
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val body = input?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_RESPONSE_BYTES) {
                        "HTTP response exceeds one MiB"
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return WireMap.encode(
                mapOf(
                    "statusCode" to WireValue.Integer(status.toLong()),
                    "body" to WireValue.Text(body),
                ),
            )
        } finally {
            connections -= connection
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 900 * 1024
    }
}
