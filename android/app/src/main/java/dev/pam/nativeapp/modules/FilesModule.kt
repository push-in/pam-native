package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

internal class FilesModule(private val activity: PamActivity) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val root = File(activity.filesDir, "pam-files").apply { mkdirs() }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "read" -> executor.execute { read(payload, completion) }
                "write" -> executor.execute { write(payload, completion) }
                "stat" -> executor.execute { stat(payload, completion) }
                "list" -> executor.execute { list(payload, completion) }
                "delete" -> executor.execute { delete(payload, completion) }
                "pick" -> pick(payload, completion)
                "pickMany" -> pickMany(payload, completion)
                "capture" -> capture(payload, completion)
                else -> error("Unknown files method $method")
            }
        }.onFailure { completion.failure(it) }
    }

    private fun stat(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val file = resolve(WireMap.decode(payload).requiredText("path"))
            require(file.isFile) { "File does not exist" }
            completion.success(file, mimeFor(file))
        }.onFailure { completion.failure(it) }
    }

    private fun list(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val path = WireMap.decode(payload).requiredText("path")
            val directory = if (path.isBlank()) root else resolve(path)
            require(directory.isDirectory) { "Directory does not exist" }
            val items = JSONArray()
            directory.listFiles()
                .orEmpty()
                .filter(File::isFile)
                .sortedBy { it.name.lowercase() }
                .forEach { file ->
                    items.put(JSONObject().apply {
                        put("path", file.relativeTo(root).path)
                        put("name", file.name)
                        put("mimeType", mimeFor(file))
                        put("size", file.length())
                    })
                }
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("items" to WireValue.Text(items.toString()))),
            )
        }.onFailure { completion.failure(it) }
    }

    private fun delete(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val file = resolve(WireMap.decode(payload).requiredText("path"))
            require(file.isFile) { "File does not exist" }
            require(file.delete()) { "Unable to delete file" }
            completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
        }.onFailure { completion.failure(it) }
    }

    private fun read(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val file = resolve(WireMap.decode(payload).requiredText("path"))
            require(file.length() <= MAX_READ_BYTES) { "File exceeds the one MiB bridge limit" }
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(
                    mapOf("data" to WireValue.Text(Base64.getEncoder().encodeToString(file.readBytes()))),
                ),
            )
        }.onFailure { completion.failure(it) }
    }

    private fun write(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val file = resolve(values.requiredText("path"))
            val bytes = Base64.getDecoder().decode(values.requiredText("data"))
            require(bytes.size <= MAX_READ_BYTES) { "File exceeds the one MiB bridge limit" }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
        }.onFailure { completion.failure(it) }
    }

    private fun pick(payload: ByteArray, completion: ModuleCompletion) {
        val type = WireMap.decode(payload).integer("type", 4)
        val mime = when (type) {
            1L -> "image/*"
            2L -> "video/*"
            3L -> "audio/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mime
        }
        activity.launchForResult(intent) { result, data ->
            if (result != Activity.RESULT_OK || data?.data == null) {
                completion.complete(ModuleResultStatus.FAILURE, "File selection was cancelled".toByteArray())
            } else {
                executor.execute { importUri(data.data!!, completion) }
            }
        }
    }

    private fun pickMany(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val type = values.integer("type", 4)
        val limit = values.integer("limit", DEFAULT_PICK_LIMIT.toLong())
            .coerceIn(1, MAX_PICK_LIMIT.toLong())
            .toInt()
        val mime = when (type) {
            1L -> "image/*"
            2L -> "video/*"
            3L -> "audio/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mime
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.launchForResult(intent) { result, data ->
            if (result != Activity.RESULT_OK || data == null) {
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    "File selection was cancelled".toByteArray(),
                )
                return@launchForResult
            }
            val uris = buildList {
                data.clipData?.let { clips ->
                    repeat(minOf(clips.itemCount, limit)) { index ->
                        add(clips.getItemAt(index).uri)
                    }
                }
                if (isEmpty()) {
                    data.data?.let(::add)
                }
            }.distinct().take(limit)
            if (uris.isEmpty()) {
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    "No file was selected".toByteArray(),
                )
                return@launchForResult
            }
            executor.execute { importUris(uris, completion) }
        }
    }

    private fun capture(payload: ByteArray, completion: ModuleCompletion) {
        val type = WireMap.decode(payload).integer("type", 1)
        val isVideo = type == 2L
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val output = activity.contentResolver.insert(
            collection,
            ContentValues().apply {
                put(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    "pam-capture-${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}",
                )
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
            },
        ) ?: error("Unable to allocate capture destination")
        val intent = Intent(
            if (isVideo) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, output)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        require(intent.resolveActivity(activity.packageManager) != null) {
            "No capture application is available"
        }
        activity.launchForResult(intent) { result, data ->
            if (result != Activity.RESULT_OK) {
                activity.contentResolver.delete(output, null, null)
                completion.complete(ModuleResultStatus.FAILURE, "Media capture was cancelled".toByteArray())
                return@launchForResult
            }
            executor.execute {
                importUri(output, completion)
                activity.contentResolver.delete(output, null, null)
            }
        }
    }

    private fun importUri(uri: Uri, completion: ModuleCompletion) {
        runCatching {
            val imported = importUri(uri)
            completion.success(imported.file, imported.mime)
        }.onFailure { completion.failure(it) }
    }

    private fun importUris(uris: List<Uri>, completion: ModuleCompletion) {
        val imported = mutableListOf<ImportedFile>()
        runCatching {
            var total = 0L
            uris.forEach { uri ->
                val remaining = MAX_MULTI_IMPORT_BYTES - total
                require(remaining > 0) { "Selected files exceed 256 MiB" }
                val item = importUri(uri, minOf(MAX_IMPORT_BYTES, remaining))
                imported += item
                total += item.file.length()
            }
            val items = JSONArray()
            imported.forEach { item ->
                items.put(JSONObject().apply {
                    put("path", item.file.relativeTo(root).path)
                    put("name", item.file.name)
                    put("mimeType", item.mime)
                    put("size", item.file.length())
                })
            }
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("items" to WireValue.Text(items.toString()))),
            )
        }.onFailure { error ->
            imported.forEach { it.file.delete() }
            completion.failure(error)
        }
    }

    private fun importUri(uri: Uri, maximumBytes: Long = MAX_IMPORT_BYTES): ImportedFile {
        var name = "document"
        val mime = activity.contentResolver.getType(uri) ?: "application/octet-stream"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        val file = uniqueImport(name)
        return try {
            activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to read selected file" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maximumBytes) {
                            if (maximumBytes < MAX_IMPORT_BYTES) {
                                "Selected files exceed 256 MiB"
                            } else {
                                "Selected file exceeds 64 MiB"
                            }
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            ImportedFile(file, mime)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun resolve(path: String): File {
        require(path.isNotBlank()) { "File path cannot be empty" }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "File path escapes sandbox" }
        return file
    }

    private fun uniqueImport(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(128).ifEmpty { "file" }
        return File(root, "imports/${UUID.randomUUID()}-$safe").apply {
            parentFile?.mkdirs()
        }
    }

    private fun ModuleCompletion.success(file: File, mime: String) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(
                mapOf(
                    "path" to WireValue.Text(file.relativeTo(root).path),
                    "name" to WireValue.Text(file.name),
                    "mimeType" to WireValue.Text(mime),
                    "size" to WireValue.Integer(file.length()),
                ),
            ),
        )
    }

    private fun mimeFor(file: File): String =
        java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"

    private fun ModuleCompletion.failure(error: Throwable) {
        complete(ModuleResultStatus.FAILURE, (error.message ?: "File operation failed").toByteArray())
    }

    private fun Map<String, WireValue>.requiredText(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: error("Missing text field $key")

    private fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
        (this[key] as? WireValue.Integer)?.value ?: fallback

    override fun close() {
        executor.shutdown()
    }

    private companion object {
        const val DEFAULT_PICK_LIMIT = 10
        const val MAX_PICK_LIMIT = 50
        const val MAX_READ_BYTES = 1024 * 1024L
        const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L
        const val MAX_MULTI_IMPORT_BYTES = 256L * 1024L * 1024L
    }

    private data class ImportedFile(val file: File, val mime: String)
}
