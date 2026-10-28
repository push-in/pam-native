package dev.pam.nativeapp.modules

import dev.pam.nativeapp.BuildConfig
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal class HttpModule(
    private val filesRoot: File? = null,
    private val uploadCache: File? = null,
) : NativeModule, AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "pam-http").apply { isDaemon = true }
    }
    private val uploadDeadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pam-http-upload-timeout").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val transferExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pam-http-transfer").apply { isDaemon = true }
    }
    private val transfers = ConcurrentHashMap<Int, HttpTransfer>()
    private val nextTransfer = java.util.concurrent.atomic.AtomicInteger(1)

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (method in TRANSFER_METHODS) {
            runCatching { transfer(method, payload, completion) }.onFailure {
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    (it.message ?: "HTTP transfer failed").toByteArray(),
                )
            }
            return
        }
        if (method !in setOf("get", "request", "upload")) {
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
                    } else if (method == "upload") {
                        "PUT"
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
                    val snapshot = if (method == "upload") {
                        require(body == null) { "File upload cannot include a text body" }
                        val path = (values["path"] as? WireValue.Text)?.value
                            ?: error("Upload source path is required")
                        HttpUploadSnapshot.create(
                            requireNotNull(filesRoot) { "Private files directory is unavailable" },
                            requireNotNull(uploadCache) { "Private upload cache is unavailable" },
                            path,
                        ) { closed.get() || Thread.currentThread().isInterrupted }
                    } else null
                    try {
                        fetch(url, requestMethod, headersJson, body, timeoutMs, traceparent, traceOrigin, snapshot?.file)
                    } finally {
                        snapshot?.close()
                    }
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

    private fun transfer(method: String, payload: ByteArray, completion: ModuleCompletion) {
        when (method) {
            "transferNext" -> transferFor(payload).channel.next(completion)
            "transferCancel" -> {
                transfers.remove(transferId(payload))?.cancel()
                completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
            }
            else -> startTransfer(payload, completion)
        }
    }

    private fun startTransfer(payload: ByteArray, completion: ModuleCompletion) {
        check(!closed.get()) { "HTTP module is closed" }
        val values = WireMap.decode(payload)
        val url = (values["url"] as? WireValue.Text)?.value ?: error("HTTP URL is required")
        val requestMethod = (values["method"] as? WireValue.Text)?.value ?: "POST"
        require(requestMethod in TRANSFER_HTTP_METHODS) { "Unsupported HTTP transfer method $requestMethod" }
        val headersJson = (values["headers"] as? WireValue.Text)?.value ?: "{}"
        val timeoutMs = ((values["timeoutMs"] as? WireValue.Integer)?.value ?: 60_000L)
            .coerceIn(1_000L, 600_000L).toInt()
        val traceparent = (values["traceparent"] as? WireValue.Text)?.value
        val traceOrigin = (values["traceOrigin"] as? WireValue.Text)?.value
        val root = requireNotNull(filesRoot) { "Private files directory is unavailable" }
        val kind = (values["kind"] as? WireValue.Integer)?.value ?: TRANSFER_MULTIPART
        val id = nextTransfer.getAndIncrement()
        val transfer = HttpTransfer()
        transfers[id] = transfer
        transfer.future = transferExecutor.submit {
            var snapshot: HttpUploadSnapshot? = null
            runCatching {
                val body: StreamedBody = if (kind == TRANSFER_MULTIPART) {
                    val multipart = MultipartBody.decode(
                        (values["parts"] as? WireValue.Text)?.value ?: error("Multipart parts are required"),
                    ) { path -> resolvePrivateFile(root, path) }
                    StreamedBody(multipart.contentType, multipart.contentLength()) { output, progress ->
                        multipart.writeTo(output, { transfer.cancelled.get() || closed.get() }, progress)
                    }
                } else {
                    val path = (values["path"] as? WireValue.Text)?.value ?: error("Upload source path is required")
                    val created = HttpUploadSnapshot.create(
                        root,
                        requireNotNull(uploadCache) { "Private upload cache is unavailable" },
                        path,
                    ) { closed.get() || transfer.cancelled.get() }
                    snapshot = created
                    val file = created.file
                    StreamedBody(null, file.length()) { output, progress ->
                        file.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var sent = 0L
                            while (true) {
                                check(!transfer.cancelled.get() && !closed.get()) { "HTTP transfer cancelled" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                sent += count
                                progress(sent)
                            }
                        }
                    }
                }
                streamTransfer(url, requestMethod, headersJson, timeoutMs, traceparent, traceOrigin, body, transfer)
            }.fold(
                onSuccess = { transfer.channel.offer(it) },
                onFailure = {
                    transfer.channel.offer(
                        HttpTransferEvents.failed(
                            if (transfer.cancelled.get()) "HTTP transfer cancelled"
                            else it.message ?: "HTTP transfer failed",
                        ),
                    )
                },
            )
            runCatching { snapshot?.close() }
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("transfer" to WireValue.Integer(id.toLong()))),
        )
    }

    private fun streamTransfer(
        source: String,
        requestMethod: String,
        headersJson: String,
        timeoutMs: Int,
        traceparent: String?,
        traceOrigin: String?,
        body: StreamedBody,
        transfer: HttpTransfer,
    ): ByteArray {
        val uri = URI(source)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "HTTP requests require HTTPS"
        }
        require(uri.userInfo == null && uri.host != null) { "Invalid HTTP URL" }
        val connection = URL(source).openConnection() as HttpURLConnection
        connections += connection
        transfer.connection = connection
        try {
            check(!transfer.cancelled.get()) { "HTTP transfer cancelled" }
            connection.requestMethod = requestMethod
            connection.connectTimeout = minOf(15_000, timeoutMs)
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            applyHeaders(connection, headersJson, fileTransport = true, multipart = body.contentType != null)
            applyTrace(connection, uri, traceparent, traceOrigin)
            body.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            if (body.contentType == null && connection.getRequestProperty("Content-Type") == null) {
                connection.setRequestProperty("Content-Type", "application/octet-stream")
            }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.length)
            val throttle = TransferProgressThrottle(body.length)
            transfer.channel.offer(HttpTransferEvents.progress(0, body.length))
            connection.outputStream.use { output ->
                body.write(output) { sent ->
                    if (throttle.shouldReport(sent)) {
                        transfer.channel.offer(HttpTransferEvents.progress(sent, body.length))
                    }
                }
            }
            check(!transfer.cancelled.get()) { "HTTP transfer cancelled" }
            val status = connection.responseCode
            return HttpTransferEvents.complete(status, readResponse(connection, status), responseHeaders(connection))
        } finally {
            transfer.connection = null
            connections -= connection
            connection.disconnect()
        }
    }

    private fun applyHeaders(
        connection: HttpURLConnection,
        headersJson: String,
        fileTransport: Boolean,
        multipart: Boolean = false,
    ) {
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
            require(!fileTransport || name.lowercase() !in FILE_TRANSPORT_HEADERS) {
                "File upload headers cannot override HTTP transport fields"
            }
            require(!multipart || name.lowercase() != "content-type") {
                "Multipart requests own the Content-Type boundary"
            }
            connection.setRequestProperty(name, value)
        }
    }

    private fun applyTrace(connection: HttpURLConnection, uri: URI, traceparent: String?, traceOrigin: String?) {
        if (traceparent == null && traceOrigin == null) return
        require(traceparent != null && traceOrigin != null) { "Incomplete HTTP trace context" }
        require(TRACEPARENT.matches(traceparent)) { "Invalid W3C version 00 traceparent" }
        require(origin(uri) == traceOrigin && traceOrigin.startsWith("https://")) {
            "Trace context origin does not match the HTTP request origin"
        }
        connection.setRequestProperty("traceparent", traceparent)
    }

    private fun readResponse(connection: HttpURLConnection, status: Int): String {
        val input = if (status >= 400) connection.errorStream else connection.inputStream
        return input?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_RESPONSE_BYTES) { "HTTP response exceeds one MiB" }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }.orEmpty()
    }

    private fun transferFor(payload: ByteArray): HttpTransfer =
        transfers[transferId(payload)] ?: error("HTTP transfer not found")

    private fun transferId(payload: ByteArray): Int =
        ((WireMap.decode(payload)["transfer"] as? WireValue.Integer)?.value
            ?: error("HTTP transfer id is required")).toInt()

    private class StreamedBody(
        val contentType: String?,
        val length: Long,
        val write: (java.io.OutputStream, (Long) -> Unit) -> Unit,
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        transfers.values.forEach(HttpTransfer::cancel)
        transfers.clear()
        transferExecutor.shutdownNow()
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        uploadDeadlines.shutdownNow()
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
        bodyFile: File?,
    ): ByteArray {
        val uri = URI(source)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "HTTP requests require HTTPS"
        }
        require(uri.userInfo == null && uri.host != null) { "Invalid HTTP URL" }
        val connection = URL(source).openConnection() as HttpURLConnection
        connections += connection
        var deadline: ScheduledFuture<*>? = null
        val timedOut = AtomicBoolean()
        try {
            if (bodyFile != null) {
                deadline = uploadDeadlines.schedule({ timedOut.set(true); connection.disconnect() }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            }
            check(!closed.get()) { "HTTP module is closed" }
            require(requestMethod in ALLOWED_METHODS) { "Unsupported HTTP method $requestMethod" }
            require(body == null || body.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) {
                "HTTP request body exceeds one MiB"
            }
            connection.requestMethod = requestMethod
            connection.connectTimeout = if (bodyFile != null) minOf(10_000, timeoutMs) else 10_000
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
                require(bodyFile == null || name.lowercase() !in FILE_TRANSPORT_HEADERS) {
                    "File upload headers cannot override HTTP transport fields"
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
            if (bodyFile != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bodyFile.length())
                bodyFile.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(!closed.get() && !timedOut.get() && !Thread.currentThread().isInterrupted) { "HTTP upload cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            } else if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(!timedOut.get()) { "HTTP upload timed out" }
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
                    "headers" to WireValue.Text(responseHeaders(connection)),
                ),
            )
        } finally {
            deadline?.cancel(false)
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
        val TRANSFER_HTTP_METHODS = setOf("POST", "PUT", "PATCH")
        val TRANSFER_METHODS = setOf("transferStart", "transferNext", "transferCancel")
        const val TRANSFER_MULTIPART = 1L
        val FILE_TRANSPORT_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "trailer", "upgrade")
        val RESERVED_TRACE_HEADERS = setOf("traceparent", "tracestate")
        val TRACEPARENT = Regex("^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$")
        val SAFE_HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
        const val MAX_REQUEST_BYTES = 1_048_576
        const val MAX_RESPONSE_BYTES = 900 * 1024
    }
}

/** Resolves a relative path strictly inside the private PAM files directory. */
internal fun resolvePrivateFile(root: File, path: String): File {
    require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path && '\u0000' !in path) {
        "File path must be relative"
    }
    require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid file path" }
    val canonicalRoot = root.canonicalFile
    val file = File(canonicalRoot, path).canonicalFile
    require(file.path.startsWith(canonicalRoot.path + File.separator) && file.isFile) {
        "File is outside the private files directory or missing"
    }
    return file
}

/** Response headers as a JSON object with lower-case names, bounded to 32 KiB. */
internal fun responseHeaders(connection: HttpURLConnection): String {
    val headers = JSONObject()
    var bytes = 0
    connection.headerFields.orEmpty().forEach { (name, values) ->
        if (name == null || values.isNullOrEmpty()) return@forEach
        val value = values.joinToString(", ")
        bytes += name.length + value.length
        if (bytes <= 32 * 1024 && headers.length() < 64) headers.put(name.lowercase(), value)
    }
    return headers.toString()
}
