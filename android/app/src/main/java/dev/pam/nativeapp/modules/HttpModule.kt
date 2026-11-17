package dev.pam.nativeapp.modules

import dev.pam.nativeapp.BuildConfig
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject

internal class HttpModule(
    private val filesRoot: File? = null,
    private val uploadCache: File? = null,
    baseClient: OkHttpClient = dev.pam.nativeapp.net.PamHttpClient.shared,
) : NativeModule, AutoCloseable {
    // OkHttp: Happy Eyeballs (RFC 8305 fast fallback: IPv6 and IPv4 attempts
    // interleaved 250 ms apart, so a network with broken IPv6 costs a quarter
    // second, not a 10-15 s connect timeout) and per-host fairness. Requests
    // run on demand-created threads that end when idle: nothing works while
    // the app makes no requests.
    private val dispatcher = Dispatcher(
        ThreadPoolExecutor(0, Int.MAX_VALUE, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "pam-http").apply { isDaemon = true }
        },
    ).apply {
        maxRequests = MAX_CONCURRENT_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }
    private val client: OkHttpClient = baseClient.newBuilder()
        .dispatcher(dispatcher)
        .build()
    private val closed = AtomicBoolean()
    private val transferExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pam-http-transfer").apply { isDaemon = true }
    }
    private val transfers = ConcurrentHashMap<Int, HttpTransfer>()
    /** In-flight requests by the cancel key PHP sent (Http::cancelRequest). */
    private val calls = ConcurrentHashMap<Long, Call>()
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
        if (method == "cancel") {
            val key = (WireMap.decode(payload)["cancelKey"] as? WireValue.Integer)?.value
            key?.let(calls::remove)?.cancel()
            completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
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
            val maxResponseBytes = ((values["maxResponseBytes"] as? WireValue.Integer)?.value
                ?: DEFAULT_RESPONSE_BYTES.toLong())
            require(maxResponseBytes in 1L..MAX_RESPONSE_LIMIT.toLong()) {
                "maxResponseBytes must be between 1 and 16 MiB"
            }
            val cancelKey = (values["cancelKey"] as? WireValue.Integer)?.value
            val uploadPath = if (method == "upload") {
                require(body == null) { "File upload cannot include a text body" }
                (values["path"] as? WireValue.Text)?.value ?: error("Upload source path is required")
            } else {
                null
            }
            val prepared = { snapshot: HttpUploadSnapshot? ->
                enqueue(
                    request(url, requestMethod, headersJson, body, traceparent, traceOrigin, snapshot?.file),
                    timeoutMs,
                    uploading = snapshot != null,
                    maxResponseBytes = maxResponseBytes.toInt(),
                    completion = completion,
                    cancelKey = cancelKey,
                    finished = { snapshot?.close() },
                )
            }
            if (uploadPath == null) {
                prepared(null)
            } else {
                // Copying the source into a private snapshot is file I/O: off
                // the calling thread, then the request joins the dispatcher.
                transferExecutor.execute {
                    runCatching {
                        HttpUploadSnapshot.create(
                            requireNotNull(filesRoot) { "Private files directory is unavailable" },
                            requireNotNull(uploadCache) { "Private upload cache is unavailable" },
                            uploadPath,
                        ) { closed.get() || Thread.currentThread().isInterrupted }
                    }.fold(
                        onSuccess = { snapshot -> runCatching { prepared(snapshot) }.onFailure { snapshot.close(); fail(completion, it) } },
                        onFailure = { fail(completion, it) },
                    )
                }
            }
        }.onFailure { fail(completion, it) }
    }

    private fun fail(completion: ModuleCompletion, error: Throwable) {
        val message = if (closed.get()) "HTTP module is closed" else error.message ?: "HTTP request failed"
        completion.complete(ModuleResultStatus.FAILURE, message.toByteArray())
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
        val headers = Headers.Builder()
        headers.add("Accept", "application/json, text/plain, */*")
        applyHeaders(headers, headersJson, fileTransport = true, multipart = body.contentType != null)
        applyTrace(headers, uri, traceparent, traceOrigin)
        val contentType = body.contentType ?: headers["Content-Type"] ?: "application/octet-stream"
        headers.removeAll("Content-Type")
        val throttle = TransferProgressThrottle(body.length)
        val requestBody = object : RequestBody() {
            override fun contentType() = contentType.toMediaTypeOrNull()
            override fun contentLength() = body.length
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                val output = sink.outputStream()
                body.write(output) { sent ->
                    if (throttle.shouldReport(sent)) {
                        transfer.channel.offer(HttpTransferEvents.progress(sent, body.length))
                    }
                }
                output.flush()
            }
        }
        check(!transfer.cancelled.get()) { "HTTP transfer cancelled" }
        val call = client.newBuilder()
            .connectTimeout(minOf(15_000, timeoutMs).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(Request.Builder().url(source).headers(headers.build()).method(requestMethod, requestBody).build())
        transfer.call = call
        try {
            transfer.channel.offer(HttpTransferEvents.progress(0, body.length))
            call.execute().use { response ->
                check(!transfer.cancelled.get()) { "HTTP transfer cancelled" }
                val text = response.body.byteStream().use { stream ->
                    String(readBounded(stream, INLINE_RESPONSE_BYTES), Charsets.UTF_8)
                }
                return HttpTransferEvents.complete(response.code, text, responseHeaders(response.headers))
            }
        } finally {
            transfer.call = null
        }
    }

    private fun applyHeaders(
        headers: Headers.Builder,
        headersJson: String,
        fileTransport: Boolean,
        multipart: Boolean = false,
    ) {
        val values = JSONObject(headersJson)
        require(values.length() <= 32) { "HTTP requests support at most 32 headers" }
        values.keys().forEach { name ->
            val value = values.getString(name)
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
            // Like HttpURLConnection.setRequestProperty: one value per name.
            headers.removeAll(name)
            headers.addUnsafeNonAscii(name, value)
        }
    }

    private fun applyTrace(headers: Headers.Builder, uri: URI, traceparent: String?, traceOrigin: String?) {
        if (traceparent == null && traceOrigin == null) return
        require(traceparent != null && traceOrigin != null) { "Incomplete HTTP trace context" }
        require(TRACEPARENT.matches(traceparent)) { "Invalid W3C version 00 traceparent" }
        require(origin(uri) == traceOrigin && traceOrigin.startsWith("https://")) {
            "Trace context origin does not match the HTTP request origin"
        }
        headers.set("traceparent", traceparent)
    }

    /** Reads at most [limit] bytes; a longer body fails with the limit in the message. */
    private fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(8_192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) {
                "HTTP response exceeds $limit bytes; raise maxResponseBytes (up to 16 MiB)"
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun spill(bytes: ByteArray): File {
        val cache = requireNotNull(uploadCache) { "Private HTTP cache is unavailable" }
        val directory = File(cache.parentFile ?: cache, "pam-http-responses").apply { mkdirs() }
        // Stale files from a request whose PHP side never read them.
        directory.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 10 * 60_000L) file.delete()
        }
        val file = File(directory, "${java.util.UUID.randomUUID()}.pamhttp")
        file.writeBytes(bytes)
        return file
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
        dispatcher.cancelAll()
        dispatcher.executorService.shutdown()
    }

    private fun request(
        source: String,
        requestMethod: String,
        headersJson: String,
        body: String?,
        traceparent: String?,
        traceOrigin: String?,
        bodyFile: File?,
    ): Request {
        val uri = URI(source)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "HTTP requests require HTTPS"
        }
        require(uri.userInfo == null && uri.host != null) { "Invalid HTTP URL" }
        check(!closed.get()) { "HTTP module is closed" }
        require(requestMethod in ALLOWED_METHODS) { "Unsupported HTTP method $requestMethod" }
        val bytes = body?.toByteArray(Charsets.UTF_8)
        require(bytes == null || bytes.size <= MAX_REQUEST_BYTES) { "HTTP request body exceeds one MiB" }
        val headers = Headers.Builder()
        headers.add("Accept", "application/json, text/plain, */*")
        applyHeaders(headers, headersJson, fileTransport = bodyFile != null)
        applyTrace(headers, uri, traceparent, traceOrigin)
        val type = headers["Content-Type"]?.toMediaTypeOrNull()
        val requestBody = when {
            bodyFile != null -> object : RequestBody() {
                override fun contentType() = type
                override fun contentLength() = bodyFile.length()
                override fun writeTo(sink: BufferedSink) {
                    bodyFile.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // IOException: OkHttp rethrows anything else on its dispatcher thread and crashes the app.
                            if (closed.get()) throw IOException("HTTP upload cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            }
            bytes != null -> bytes.toRequestBody(type)
            // OkHttp requires a body for these methods; HttpURLConnection sent an empty one.
            requestMethod in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(type)
            else -> null
        }
        return Request.Builder()
            .url(source)
            .headers(headers.build())
            .method(requestMethod, requestBody)
            .build()
    }

    private fun enqueue(
        request: Request,
        timeoutMs: Int,
        uploading: Boolean,
        maxResponseBytes: Int,
        completion: ModuleCompletion,
        cancelKey: Long?,
        finished: () -> Unit,
    ) {
        val builder = client.newBuilder()
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (uploading) {
            // The whole upload, connect included, has the request's deadline.
            builder.connectTimeout(minOf(10_000, timeoutMs).toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }
        val call = builder.build().newCall(request)
        cancelKey?.let { calls[it] = call }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cancelKey?.let { calls.remove(it, call) }
                    finished()
                    val message = when {
                        closed.get() -> "HTTP module is closed"
                        uploading && call.isCanceled() -> "HTTP upload cancelled"
                        uploading && e is java.io.InterruptedIOException && e.message == "timeout" -> "HTTP upload timed out"
                        else -> e.message ?: "HTTP request failed"
                    }
                    completion.complete(ModuleResultStatus.FAILURE, message.toByteArray())
                }

                override fun onResponse(call: Call, response: Response) {
                    cancelKey?.let { calls.remove(it, call) }
                    runCatching {
                        response.use { encodeResponse(it, maxResponseBytes) }
                    }.also { finished() }.fold(
                        onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                        onFailure = { fail(completion, it) },
                    )
                }
            },
        )
    }

    private fun encodeResponse(response: Response, maxResponseBytes: Int): ByteArray {
        val bytes = response.body.byteStream().use { stream -> readBounded(stream, maxResponseBytes) }
        val result = linkedMapOf<String, WireValue>(
            "statusCode" to WireValue.Integer(response.code.toLong()),
            "headers" to WireValue.Text(responseHeaders(response.headers)),
        )
        if (bytes.size <= INLINE_RESPONSE_BYTES) {
            result["body"] = WireValue.Text(String(bytes, Charsets.UTF_8))
        } else {
            // The module bridge carries at most 1 MiB: a larger body goes
            // through a private cache file that PHP reads once and deletes.
            result["body"] = WireValue.Text("")
            result["bodyFile"] = WireValue.Text(spill(bytes).absolutePath)
        }
        return WireMap.encode(result)
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
        /** Largest body sent inline over the module bridge (1 MiB frames). */
        const val INLINE_RESPONSE_BYTES = 900 * 1024
        const val DEFAULT_RESPONSE_BYTES = 1_048_576
        const val MAX_RESPONSE_LIMIT = 16 * 1024 * 1024
        /** Concurrent requests per host (browsers use 6) and overall. */
        const val MAX_REQUESTS_PER_HOST = 6
        const val MAX_CONCURRENT_REQUESTS = 32
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
internal fun responseHeaders(headers: Headers): String {
    val json = JSONObject()
    var bytes = 0
    headers.names().forEach { name ->
        val value = headers.values(name).joinToString(", ")
        bytes += name.length + value.length
        if (bytes <= 32 * 1024 && json.length() < 64) json.put(name.lowercase(), value)
    }
    return json.toString()
}
