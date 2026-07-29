package dev.pam.nativeapp.modules

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

internal object PamIncomingShares {
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var initial: ByteArray? = null
    private var waiter: ModuleCompletion? = null

    fun captureInitial(context: Context, intent: Intent?) {
        val payload = capture(context, intent) ?: return
        synchronized(lock) {
            if (initial == null) initial = payload
        }
    }

    fun reportOpened(context: Context, intent: Intent?) {
        val payload = capture(context, intent) ?: return
        val pending = synchronized(lock) {
            val value = waiter
            if (value == null) {
                if (events.size >= MAX_QUEUED_EVENTS) events.removeFirst()
                events.addLast(payload)
            } else {
                waiter = null
            }
            value
        }
        pending?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    fun initial(completion: ModuleCompletion) {
        val payload = synchronized(lock) {
            val value = initial
            initial = null
            value
        } ?: emptyPayload()
        completion.complete(ModuleResultStatus.SUCCESS, payload)
    }

    fun next(completion: ModuleCompletion) {
        val event = synchronized(lock) {
            require(waiter == null) { "Only one incoming-share listener can wait at a time" }
            if (events.isEmpty()) {
                waiter = completion
                null
            } else {
                events.removeFirst()
            }
        }
        if (event != null) completion.complete(ModuleResultStatus.SUCCESS, event)
    }

    fun close(message: String) {
        val pending = synchronized(lock) {
            val value = waiter
            waiter = null
            value
        }
        pending?.complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    private fun capture(context: Context, intent: Intent?): ByteArray? {
        if (intent == null || intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            return null
        }
        val uris = linkedSetOf<Uri>()
        intent.clipData?.appendUris(uris)
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.parcelableUriList(Intent.EXTRA_STREAM).forEach(uris::add)
        } else {
            intent.parcelableUri(Intent.EXTRA_STREAM)?.let(uris::add)
        }
        val files = JSONArray()
        uris.take(MAX_FILES).forEach { uri ->
            runCatching { copyIntoSandbox(context, uri, intent.type) }
                .getOrNull()
                ?.let(files::put)
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(MAX_TEXT_BYTES)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(MAX_SUBJECT_BYTES)
        if (files.length() == 0 && text.isBlank() && subject.isBlank()) return null

        return WireMap.encode(
            mapOf(
                "available" to WireValue.Flag(true),
                "text" to WireValue.Text(text),
                "subject" to WireValue.Text(subject),
                "mimeType" to WireValue.Text(intent.type.orEmpty()),
                "files" to WireValue.Text(files.toString()),
            ),
        )
    }

    private fun copyIntoSandbox(context: Context, uri: Uri, fallbackMime: String?): JSONObject {
        val metadata = queryMetadata(context, uri)
        val mime = context.contentResolver.getType(uri)
            ?: fallbackMime
            ?: "application/octet-stream"
        val directory = File(context.cacheDir, "pam-incoming-shares").apply { mkdirs() }
        val safeName = metadata.first
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "shared-${System.nanoTime()}" }
        val target = File.createTempFile("incoming-", "-$safeName", directory)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open shared file" }
            target.outputStream().buffered().use(input::copyTo)
        }
        return JSONObject()
            .put("path", target.absolutePath)
            .put("name", metadata.first)
            .put("mimeType", mime)
            .put("size", target.length())
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        val cursor: Cursor? = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return (name.ifBlank { "shared-file" }) to size
    }

    private fun ClipData.appendUris(target: MutableSet<Uri>) {
        repeat(itemCount) { index -> getItemAt(index).uri?.let(target::add) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUri(key: String): Uri? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java)
        else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableUriList(key: String): List<Uri> =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(key).orEmpty()
        }

    private fun emptyPayload(): ByteArray = WireMap.encode(
        mapOf(
            "available" to WireValue.Flag(false),
            "text" to WireValue.Text(""),
            "subject" to WireValue.Text(""),
            "mimeType" to WireValue.Text(""),
            "files" to WireValue.Text("[]"),
        ),
    )

    private const val MAX_QUEUED_EVENTS = 16
    private const val MAX_FILES = 10
    private const val MAX_TEXT_BYTES = 65_536
    private const val MAX_SUBJECT_BYTES = 4_096
}
