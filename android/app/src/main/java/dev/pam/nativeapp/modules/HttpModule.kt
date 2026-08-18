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
import org.json.JSONObject

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
        if (method != "get" && method != "request") {
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
                    val requestMethod = if (method == "get") {
                        "GET"
                    } else {
                        (values["method"] as? WireValue.Text)?.value
                            ?: error("HTTP method is required")
                    }
                    val headersJson = (values["headers"] as? WireValue.Text)?.value ?: "{}"
                    val body = (values["body"] as? WireValue.Text)?.value
                    val traceparent = (values["traceparent"] as? WireValue.Text)?.value
                    val traceOrigin = (values["traceOrigin"] as? WireValue.Text)?.value
                    val timeoutMs = ((values["timeoutMs"] as? WireValue.Integer)?.value ?: 30_000L)
                        .coerceIn(1_000L, 120_000L)
                        .toInt()
                    fetch(url, requestMethod, headersJson, body, timeoutMs, traceparent, traceOrigin)
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

    private fun fetch(
        source: String,
        requestMethod: String,
        headersJson: String,
        body: String?,
        timeoutMs: Int,
        traceparent: String?,
        traceOrigin: String?,
    ): ByteArray {
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
        require(requestMethod in ALLOWED_METHODS) { "Unsupported HTTP method $requestMethod" }
        require(body == null || body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "HTTP request body exceeds one MiB"
        }
        connection.requestMethod = requestMethod
        connection.connectTimeout = 10_000
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        val headers = JSONObject(headersJson)
        require(headers.length() <= 32) { "HTTP requests support at most 32 headers" }
        headers.keys().forEach { name ->
            val value = headers.getString(name)
            require(SAFE_HEADER_NAME.matches(name) && !value.contains('\r') && !value.contains('\n')) {
                "Invalid HTTP header"
            }
            require(value.toByteArray(Charsets.UTF_8).size <= 8_192) { "HTTP header value is too large" }
            require(name.lowercase() !in RESERVED_TRACE_HEADERS) {
                "Trace headers require an origin-scoped context"
            }
            connection.setRequestProperty(name, value)
        }
        if (traceparent != null || traceOrigin != null) {
            require(traceparent != null && traceOrigin != null) { "Incomplete HTTP trace context" }
            require(TRACEPARENT.matches(traceparent)) { "Invalid W3C version 00 traceparent" }
            require(origin(uri) == traceOrigin && traceOrigin.startsWith("https://")) {
                "Trace context origin does not match the HTTP request origin"
            }
            connection.setRequestProperty("traceparent", traceparent)
        }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
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
        fun origin(uri: URI): String {
            val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
            val rawHost = uri.host.lowercase()
            val host = if (':' in rawHost) "[$rawHost]" else rawHost
            return "${uri.scheme.lowercase()}://$host$port"
        }

        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
        val RESERVED_TRACE_HEADERS = setOf("traceparent", "tracestate")
        val TRACEPARENT = Regex("^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$")
        val SAFE_HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
        const val MAX_REQUEST_BYTES = 1_048_576
        const val MAX_RESPONSE_BYTES = 900 * 1024
    }
}
