package dev.pam.nativeapp.modules

import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

/** One part of a streamed multipart/form-data request body. */
internal sealed interface MultipartPart {
    val name: String

    data class Field(override val name: String, val value: String) : MultipartPart

    data class FilePart(
        override val name: String,
        val file: File,
        val filename: String,
        val mimeType: String,
    ) : MultipartPart
}

/**
 * Fixed-length multipart body. Files are streamed from disk, never buffered,
 * so the declared Content-Length must match the bytes eventually written.
 */
internal class MultipartBody(
    val parts: List<MultipartPart>,
    val boundary: String = "pam-${UUID.randomUUID()}",
) {
    private val fileLengths = parts.filterIsInstance<MultipartPart.FilePart>()
        .associateWith { it.file.length() }

    init {
        require(parts.isNotEmpty()) { "Multipart requests require at least one part" }
        require(parts.size <= MAX_PARTS) { "Multipart requests support at most $MAX_PARTS parts" }
        require(BOUNDARY.matches(boundary)) { "Invalid multipart boundary" }
        parts.forEach { part ->
            require(SAFE_NAME.matches(part.name)) { "Invalid multipart field name" }
            if (part is MultipartPart.FilePart) {
                require(part.file.isFile) { "Multipart file does not exist" }
                require(SAFE_FILENAME.matches(part.filename)) { "Invalid multipart filename" }
                require(MIME.matches(part.mimeType)) { "Invalid multipart MIME type" }
            } else if (part is MultipartPart.Field) {
                require(part.value.toByteArray(Charsets.UTF_8).size <= MAX_FIELD_BYTES) {
                    "Multipart field exceeds 1 MiB"
                }
            }
        }
        require(contentLength() <= MAX_BODY_BYTES) { "Multipart body exceeds 2 GiB" }
    }

    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun contentLength(): Long = parts.sumOf { part ->
        header(part).size.toLong() + when (part) {
            is MultipartPart.Field -> part.value.toByteArray(Charsets.UTF_8).size.toLong()
            is MultipartPart.FilePart -> fileLengths.getValue(part)
        } + CRLF.size
    } + closing().size

    /** Writes the body, reporting the cumulative byte count after every chunk. */
    fun writeTo(output: OutputStream, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        var written = 0L
        fun emit(bytes: ByteArray) {
            check(!cancelled()) { "HTTP transfer cancelled" }
            output.write(bytes)
            written += bytes.size
            progress(written)
        }
        parts.forEach { part ->
            emit(header(part))
            when (part) {
                is MultipartPart.Field -> emit(part.value.toByteArray(Charsets.UTF_8))
                is MultipartPart.FilePart -> {
                    val expected = fileLengths.getValue(part)
                    var copied = 0L
                    part.file.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            check(!cancelled()) { "HTTP transfer cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= expected) { "Multipart file changed during upload" }
                            output.write(buffer, 0, count)
                            written += count
                            progress(written)
                        }
                    }
                    require(copied == expected) { "Multipart file changed during upload" }
                }
            }
            emit(CRLF)
        }
        emit(closing())
        output.flush()
    }

    private fun header(part: MultipartPart): ByteArray = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"").append(quoted(part.name)).append('"')
        if (part is MultipartPart.FilePart) {
            append("; filename=\"").append(quoted(part.filename)).append('"')
            append("\r\nContent-Type: ").append(part.mimeType)
        }
        append("\r\n\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun closing(): ByteArray = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    internal companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)
        private val BOUNDARY = Regex("^[A-Za-z0-9'()+_,./:=?-]{1,70}$")
        private val SAFE_NAME = Regex("^[^\\r\\n\\u0000]{1,256}$")
        private val SAFE_FILENAME = Regex("^[^\\r\\n\\u0000/\\\\]{1,255}$")
        private val MIME = Regex("^[A-Za-z0-9!#$&^_.+-]{1,127}/[A-Za-z0-9!#$&^_.+-]{1,127}$")
        const val MAX_PARTS = 64
        const val MAX_FIELD_BYTES = 1_048_576
        const val MAX_BODY_BYTES = 2L * 1024 * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024

        private fun quoted(value: String): String = value.replace("\"", "%22")

        /** Decodes the bridge part list, resolving files strictly inside the private sandbox. */
        fun decode(partsJson: String, resolve: (String) -> File): MultipartBody {
            val parts = JSONArray(partsJson)
            return MultipartBody(
                (0 until parts.length()).map { index ->
                    val part = parts.getJSONObject(index)
                    val name = part.getString("name")
                    when (part.getInt("type")) {
                        PART_FIELD -> MultipartPart.Field(name, part.getString("value"))
                        PART_FILE -> {
                            val file = resolve(part.getString("path"))
                            MultipartPart.FilePart(
                                name = name,
                                file = file,
                                filename = part.optString("filename").ifBlank { file.name },
                                mimeType = part.optString("mimeType").ifBlank {
                                    java.net.URLConnection.guessContentTypeFromName(file.name)
                                        ?: "application/octet-stream"
                                },
                            )
                        }
                        else -> error("Unknown multipart part type")
                    }
                },
            )
        }

        const val PART_FIELD = 1
        const val PART_FILE = 2
    }
}

/** Throttles upload progress to whole-percent steps or 256 KiB, whichever is larger. */
internal class TransferProgressThrottle(private val total: Long) {
    private var reported = -1L
    private val step = maxOf(256L * 1024, total / 100)

    fun shouldReport(sent: Long): Boolean {
        if (sent >= total || reported < 0 || sent - reported >= step) {
            if (sent == reported) return false
            reported = sent
            return true
        }
        return false
    }
}

internal class HttpTransfer(val channel: WatchChannel = WatchChannel()) {
    val cancelled = AtomicBoolean()
    @Volatile var future: Future<*>? = null
    @Volatile var connection: java.net.HttpURLConnection? = null

    fun cancel() {
        cancelled.set(true)
        connection?.disconnect()
        future?.cancel(true)
        channel.close()
    }
}

internal object HttpTransferEvents {
    const val PROGRESS = 1L
    const val COMPLETE = 2L
    const val FAILED = 3L

    fun progress(sent: Long, total: Long): ByteArray = WireMap.encode(
        mapOf(
            "state" to WireValue.Integer(PROGRESS),
            "bytesSent" to WireValue.Integer(sent),
            "totalBytes" to WireValue.Integer(total),
        ),
    )

    fun complete(status: Int, body: String, headers: String = "{}"): ByteArray = WireMap.encode(
        mapOf(
            "state" to WireValue.Integer(COMPLETE),
            "statusCode" to WireValue.Integer(status.toLong()),
            "body" to WireValue.Text(body),
            "headers" to WireValue.Text(headers),
        ),
    )

    fun failed(message: String): ByteArray = WireMap.encode(
        mapOf(
            "state" to WireValue.Integer(FAILED),
            "message" to WireValue.Text(message),
        ),
    )
}
