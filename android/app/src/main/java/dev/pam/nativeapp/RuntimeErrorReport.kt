package dev.pam.nativeapp

import org.json.JSONArray
import org.json.JSONObject

/**
 * One uncaught runtime error as reported by PHP (`PAMERR1` JSON, version 1 or
 * 2) or by the native host (plain text). Paths are app-relative and frames
 * are classified so the overlay can show the app frame first and collapse
 * framework/vendor frames.
 */
internal data class RuntimeErrorReport(
    val type: String,
    val message: String,
    val file: String,
    val line: Int,
    val column: Int,
    val phase: String,
    val fatal: Boolean,
    val frames: List<Frame>,
    val appFrame: Int?,
    val snippet: Snippet?,
    val fingerprint: String,
) {
    data class Frame(val file: String?, val line: Int, val call: String, val kind: String) {
        val isApp: Boolean get() = kind == KIND_APP
        val location: String get() = if (file == null) "[internal function]" else "$file:$line"
    }

    data class Snippet(val file: String, val line: Int, val start: Int, val lines: List<String>)

    val shortType: String get() = type.substringAfterLast('\\')

    val location: String get() = if (line > 0) "$file:$line" else file

    val phaseLabel: String get() = when (phase) {
        "boot" -> "Boot error"
        "render" -> "Render error"
        "event" -> "Event handler error"
        "module" -> "Module callback error"
        "hot-reload" -> "Hot reload error"
        "native" -> "Native runtime error"
        else -> "Uncaught error"
    }

    /** Plain-text report for the clipboard and logcat. */
    fun copyText(): String = buildString {
        append(type).append(": ").append(message).append('\n')
        append("at ").append(location).append(" (").append(phaseLabel)
        append(if (fatal) ", fatal" else "").append(")\n")
        snippet?.let { source ->
            append('\n')
            source.lines.forEachIndexed { offset, text ->
                val number = source.start + offset
                append(if (number == source.line) "> " else "  ")
                append(number.toString().padStart(4)).append(" | ").append(text).append('\n')
            }
        }
        if (frames.isNotEmpty()) {
            append('\n')
            frames.forEachIndexed { index, frame ->
                append('#').append(index).append(' ').append(frame.location)
                append(": ").append(frame.call).append('\n')
            }
        }
    }.trimEnd()

    companion object {
        const val KIND_APP = "app"
        const val KIND_FRAMEWORK = "framework"
        const val KIND_VENDOR = "vendor"
        const val KIND_INTERNAL = "internal"
        private const val PREFIX = "PAMERR1\n"
        private const val MAX_TEXT = 12_000
        private const val MAX_FRAMES = 128
        private val BUNDLE_PREFIX = Regex("^.*?/files/pam/(?:ota-)?releases/[^/]+/")
        private val TRACE_LINE = Regex("^#\\d+\\s+(?:(.+)\\((\\d+)\\)|\\[internal function\\]|\\{main\\})(?::\\s*(.*))?$")

        fun shortenPath(path: String): String = BUNDLE_PREFIX.replaceFirst(path, "")

        fun parse(raw: String): RuntimeErrorReport {
            if (!raw.startsWith(PREFIX)) return native(raw)
            return runCatching { fromJson(JSONObject(raw.substring(PREFIX.length))) }
                .getOrElse { native(raw.substring(PREFIX.length)) }
        }

        fun native(message: String, fatal: Boolean = true, phase: String = "native"): RuntimeErrorReport {
            val text = message.take(MAX_TEXT).ifBlank { "Pam Native runtime error" }
            return RuntimeErrorReport(
                type = "NativeRuntimeError",
                message = text,
                file = "Native runtime",
                line = 0,
                column = 0,
                phase = phase,
                fatal = fatal,
                frames = emptyList(),
                appFrame = null,
                snippet = null,
                fingerprint = "native:${text.hashCode()}",
            )
        }

        private fun fromJson(json: JSONObject): RuntimeErrorReport {
            val type = json.optString("type", "PHP error")
            val message = json.optString("message", "Unknown PHP error").take(MAX_TEXT)
            val file = shortenPath(json.optString("file", "<unknown>"))
            val line = json.optInt("line", 0)
            val frames = json.optJSONArray("frames")?.let(::framesFromJson)
                ?: framesFromTrace(json.optString("trace"))
            val appFrame = if (json.has("appFrame") && !json.isNull("appFrame")) {
                json.optInt("appFrame").takeIf { it in frames.indices }
            } else {
                frames.indexOfFirst(Frame::isApp).takeIf { it >= 0 }
            }
            val phase = json.optString("phase", "")
            return RuntimeErrorReport(
                type = type,
                message = message,
                file = file,
                line = line,
                column = json.optInt("column", 1),
                phase = phase.ifEmpty { "other" },
                // Version 1 payloads carried no phase: keep their historical
                // full-screen treatment.
                fatal = json.optBoolean("fatal", phase.isEmpty()),
                frames = frames,
                appFrame = appFrame,
                snippet = json.optJSONObject("snippet")?.let(::snippetFromJson),
                fingerprint = json.optString("fingerprint", "").ifEmpty {
                    "$type\u0000$message\u0000$file\u0000$line".hashCode().toString()
                },
            )
        }

        private fun framesFromJson(array: JSONArray): List<Frame> =
            (0 until minOf(array.length(), MAX_FRAMES)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val file = if (item.isNull("file")) null else shortenPath(item.optString("file"))
                Frame(
                    file = file,
                    line = item.optInt("line", 0),
                    call = item.optString("call", ""),
                    kind = item.optString("kind", classify(file)),
                )
            }

        private fun snippetFromJson(json: JSONObject): Snippet? {
            val lines = json.optJSONArray("lines") ?: return null
            return Snippet(
                file = shortenPath(json.optString("file")),
                line = json.optInt("line"),
                start = json.optInt("start", 1),
                lines = (0 until minOf(lines.length(), 32)).map { lines.optString(it) },
            )
        }

        /** Legacy `getTraceAsString()` text: `#0 /path/File.php(12): call()`. */
        internal fun framesFromTrace(trace: String): List<Frame> =
            trace.lineSequence().take(MAX_FRAMES).mapNotNull { text ->
                val match = TRACE_LINE.matchEntire(text.trim()) ?: return@mapNotNull null
                val file = match.groupValues[1].takeIf { it.isNotEmpty() }?.let(::shortenPath)
                val call = match.groupValues[3].ifEmpty { if (text.contains("{main}")) "{main}" else "" }
                Frame(file, match.groupValues[2].toIntOrNull() ?: 0, call, classify(file))
            }.toList()

        private fun classify(file: String?): String = when {
            file == null -> KIND_INTERNAL
            file.contains("vendor/pushinbr/pam-native") || file.contains("packages/native/src/") -> KIND_FRAMEWORK
            file.contains("vendor/") -> KIND_VENDOR
            else -> KIND_APP
        }
    }
}
