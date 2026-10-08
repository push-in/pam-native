package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

internal class FilesModule(private val activity: PamActivity) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val downloadExecutor = Executors.newCachedThreadPool()
    private val nextDownloadId = AtomicInteger(1)
    private val downloads = ConcurrentHashMap<Int, DownloadTask>()
    private val root = File(activity.filesDir, "pam-files").apply { mkdirs() }.canonicalFile

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "read" -> executor.execute { read(payload, completion) }
                "sha256" -> executor.execute { sha256(payload, completion) }
                "write" -> executor.execute { write(payload, completion) }
                "copyAsset" -> executor.execute { copyAsset(payload, completion) }
                "download" -> executor.execute { download(payload, completion) }
                "downloadStart" -> startDownload(payload, completion)
                "downloadNext" -> downloadTask(payload).channel.next(completion)
                "downloadCancel" -> cancelDownload(subscription(payload), completion)
                "open" -> open(payload, completion)
                "stat" -> executor.execute { stat(payload, completion) }
                "list" -> executor.execute { list(payload, completion) }
                "delete" -> executor.execute { delete(payload, completion) }
                "move" -> executor.execute { transfer(payload, completion, move = true) }
                "copy" -> executor.execute { transfer(payload, completion, move = false) }
                "makeDirectory" -> executor.execute { makeDirectory(payload, completion) }
                "share" -> share(payload, completion)
                "pick" -> pick(payload, completion)
                "pickMany" -> pickMany(payload, completion)
                "importUri" -> executor.execute { importContentUri(payload, completion) }
                "capture" -> capture(payload, completion)
                else -> error("Unknown files method $method")
            }
        }.onFailure { completion.failure(it) }
    }

    private fun sha256(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val digest = PrivateFileSha256.digest(root, WireMap.decode(payload).requiredText("path"))
            completion.complete(ModuleResultStatus.SUCCESS, WireMap.encode(mapOf("sha256" to WireValue.Text(digest))))
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

    private fun transfer(payload: ByteArray, completion: ModuleCompletion, move: Boolean) {
        runCatching {
            val values = WireMap.decode(payload)
            val source = resolve(values.requiredText("from"))
            val target = resolve(values.requiredText("to"))
            val overwrite = (values["overwrite"] as? WireValue.Flag)?.value ?: false
            transferPrivateFile(source, target, move, overwrite)
            completion.success(target, mimeFor(target))
        }.onFailure { completion.failure(it) }
    }

    private fun makeDirectory(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val directory = resolve(WireMap.decode(payload).requiredText("path"))
            require(!directory.isFile) { "A file already exists at this path" }
            require(directory.isDirectory || directory.mkdirs()) { "Unable to create directory" }
            completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(mapOf("path" to WireValue.Text(relativeSandboxPath(root, directory)))),
            )
        }.onFailure { completion.failure(it) }
    }

    private fun share(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val paths = JSONArray(values.requiredText("paths"))
        require(paths.length() in 1..MAX_SHARE_FILES) { "Share between 1 and $MAX_SHARE_FILES files" }
        val files = (0 until paths.length()).map { index ->
            resolve(paths.getString(index)).also { require(it.isFile) { "File does not exist" } }
        }
        val requestedMime = (values["mimeType"] as? WireValue.Text)?.value?.trim().orEmpty()
        val mime = requestedMime.ifEmpty { sharedMimeType(files.map(::mimeFor)) }
        val authority = "${activity.packageName}.pam.files"
        val uris = files.map { FileProvider.getUriForFile(activity, authority, it) }
        val intent = shareFilesIntent(uris, mime)
        val title = (values["title"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
        val chooser = Intent.createChooser(intent, title).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread {
            runCatching { activity.startActivity(chooser) }
                .onSuccess { completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0)) }
                .onFailure { completion.failure(it) }
        }
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

    private fun copyAsset(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val assetPath = normalizedBundledAssetPath(values.requiredText("assetPath"))
            val file = resolve(values.requiredText("path"))
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
            try {
                activity.assets.open("pam/$assetPath").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temporary.delete()
            }
            completion.success(file, mimeFor(file))
        }.onFailure { completion.failure(it) }
    }

    private fun download(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val uri = URI(values.requiredText("url"))
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "Download URL must be an absolute HTTPS URL"
            }
            require(uri.userInfo == null) { "Download URL cannot contain credentials" }
            val maximumBytes = values.integer("maximumBytes", MAX_IMPORT_BYTES)
            require(maximumBytes in 1..MAX_DOWNLOAD_BYTES) { "Invalid download size limit" }
            val file = resolve(values.requiredText("path"))
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept-Encoding", "identity")
                requestHeaders(values).forEach(connection::setRequestProperty)
                connection.connect()
                require(connection.responseCode in 200..299) {
                    "Download failed with HTTP ${connection.responseCode}"
                }
                val declaredSize = connection.contentLengthLong
                require(declaredSize < 0 || declaredSize <= maximumBytes) {
                    "Download exceeds configured size limit"
                }
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= maximumBytes) { "Download exceeds configured size limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                val mime = connection.contentType?.substringBefore(';')?.trim()
                    ?.takeIf(String::isNotEmpty) ?: mimeFor(file)
                completion.success(file, mime)
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }.onFailure { completion.failure(it) }
    }

    private fun startDownload(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val uri = URI(values.requiredText("url"))
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Download URL must be an absolute HTTPS URL"
        }
        require(uri.userInfo == null) { "Download URL cannot contain credentials" }
        val maximumBytes = values.integer("maximumBytes", MAX_IMPORT_BYTES)
        require(maximumBytes in 1..MAX_DOWNLOAD_BYTES) { "Invalid download size limit" }
        val file = resolve(values.requiredText("path"))
        val headers = requestHeaders(values)
        val id = nextDownloadId.getAndIncrement()
        val task = DownloadTask(channel = WatchChannel())
        downloads[id] = task
        task.future = downloadExecutor.submit {
            runCatching {
                performObservedDownload(uri, file, maximumBytes, headers, task.channel)
            }.onFailure { error ->
                task.channel.offer(downloadEvent(
                    state = DOWNLOAD_FAILED,
                    message = error.message ?: "Download failed",
                ))
            }
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("subscription" to WireValue.Integer(id.toLong()))),
        )
    }

    private fun performObservedDownload(
        uri: URI,
        file: File,
        maximumBytes: Long,
        headers: Map<String, String>,
        channel: WatchChannel,
    ) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp-${UUID.randomUUID()}")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            headers.forEach(connection::setRequestProperty)
            connection.connect()
            require(connection.responseCode in 200..299) {
                "Download failed with HTTP ${connection.responseCode}"
            }
            val declaredSize = connection.contentLengthLong
            require(declaredSize < 0 || declaredSize <= maximumBytes) {
                "Download exceeds configured size limit"
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var reported = -1
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Download cancelled" }
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maximumBytes) { "Download exceeds configured size limit" }
                        output.write(buffer, 0, read)
                        val percent = if (declaredSize > 0) ((total * 100) / declaredSize).toInt() else 0
                        if (percent != reported) {
                            reported = percent
                            channel.offer(downloadEvent(
                                state = DOWNLOAD_PROGRESS,
                                bytesWritten = total,
                                totalBytes = declaredSize.coerceAtLeast(0),
                            ))
                        }
                    }
                }
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            val mime = connection.contentType?.substringBefore(';')?.trim()
                ?.takeIf(String::isNotEmpty) ?: mimeFor(file)
            channel.offer(downloadEvent(
                state = DOWNLOAD_COMPLETE,
                file = file,
                mime = mime,
            ))
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun downloadEvent(
        state: Long,
        bytesWritten: Long = 0,
        totalBytes: Long = 0,
        file: File? = null,
        mime: String = "application/octet-stream",
        message: String = "",
    ): ByteArray {
        val values = mutableMapOf<String, WireValue>(
            "state" to WireValue.Integer(state),
            "bytesWritten" to WireValue.Integer(bytesWritten),
            "totalBytes" to WireValue.Integer(totalBytes),
        )
        if (file != null) {
            values["path"] = WireValue.Text(relativeSandboxPath(root, file))
            values["name"] = WireValue.Text(file.name)
            values["mimeType"] = WireValue.Text(mime)
            values["size"] = WireValue.Integer(file.length())
        }
        if (message.isNotEmpty()) values["message"] = WireValue.Text(message)
        return WireMap.encode(values)
    }

    private fun cancelDownload(id: Int, completion: ModuleCompletion) {
        downloads.remove(id)?.let { task ->
            task.future?.cancel(true)
            task.channel.close()
        }
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun downloadTask(payload: ByteArray): DownloadTask =
        downloads[subscription(payload)] ?: error("File download not found")

    private fun subscription(payload: ByteArray): Int =
        ((WireMap.decode(payload)["subscription"] as? WireValue.Integer)?.value
            ?: error("File download subscription is required")).toInt()

    private fun requestHeaders(values: Map<String, WireValue>): Map<String, String> {
        val raw = (values["headers"] as? WireValue.Text)?.value.orEmpty()
        if (raw.isBlank()) return emptyMap()
        val json = JSONObject(raw)
        require(json.length() <= 32) { "Downloads accept at most 32 request headers" }
        return buildMap {
            json.keys().forEach { name ->
                val value = json.getString(name)
                require(HEADER_NAME.matches(name) && value.length <= 8_192 && !value.contains('\r') && !value.contains('\n')) {
                    "Download request headers are invalid or unsafe"
                }
                require(name.lowercase() !in BLOCKED_HEADERS) { "Download request header is not allowed" }
                put(name, value)
            }
        }
    }

    private fun open(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val file = resolve(values.requiredText("path"))
        require(file.isFile) { "File does not exist" }
        val mime = (values["mimeType"] as? WireValue.Text)?.value
            ?.trim()?.takeIf(String::isNotEmpty) ?: mimeFor(file)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.pam.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        require(intent.resolveActivity(activity.packageManager) != null) {
            "No application can open this file type"
        }
        activity.startActivity(intent)
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun pick(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val type = values.integer("type", 4)
        val maximumBytes = values.integer("maximumBytes", MAX_IMPORT_BYTES)
        require(maximumBytes in 1..MAX_PICK_IMPORT_BYTES) { "Picker import limit must be between 1 byte and 8 GiB" }
        val requestedMime = (values["mimeType"] as? WireValue.Text)?.value.orEmpty()
        require(requestedMime.isEmpty() || PICKER_MIME.matches(requestedMime)) { "Picker MIME type is invalid" }
        val mime = requestedMime.ifEmpty { when (type) {
            1L -> "image/*"
            2L -> "video/*"
            3L -> "audio/*"
            else -> "*/*"
        } }
        val intent = (if (requestedMime.isEmpty()) visualMediaIntent(type, 1) else null)
            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = mime
                if (requestedMime.isEmpty() && type == 5L) {
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                }
            }
        activity.launchForResult(intent) { result, data ->
            if (result != Activity.RESULT_OK || data?.data == null) {
                completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
            } else {
                executor.execute { importUri(data.data!!, completion, maximumBytes) }
            }
        }
    }

    /**
     * Photos/videos open the system Photo Picker (Android 13+, or older devices
     * with the Google Play backport), like PHPicker on iOS. Without it, the
     * caller keeps the document picker.
     */
    private fun visualMediaIntent(type: Long, limit: Int): Intent? {
        val mediaType = when (type) {
            1L -> ActivityResultContracts.PickVisualMedia.ImageOnly
            2L -> ActivityResultContracts.PickVisualMedia.VideoOnly
            5L -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else -> return null
        }
        if (!ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity)) return null
        val request = PickVisualMediaRequest.Builder().setMediaType(mediaType).build()
        val maximum = photoPickerSelectionLimit(limit)
        return if (maximum > 1) {
            ActivityResultContracts.PickMultipleVisualMedia(maximum).createIntent(activity, request)
        } else {
            ActivityResultContracts.PickVisualMedia().createIntent(activity, request)
        }
    }

    private fun photoPickerSelectionLimit(limit: Int): Int {
        val platformMaximum = if (android.os.Build.VERSION.SDK_INT >= 33) {
            MediaStore.getPickImagesMaxLimit()
        } else {
            limit
        }
        return limit.coerceIn(1, platformMaximum.coerceAtLeast(1))
    }

    private fun pickMany(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val type = values.integer("type", 4)
        val limit = values.integer("limit", DEFAULT_PICK_LIMIT.toLong())
            .coerceIn(1, MAX_PICK_LIMIT.toLong())
            .toInt()
        val maximumBytes = values.integer("maximumBytes", MAX_IMPORT_BYTES)
        require(maximumBytes in 1..MAX_PICK_IMPORT_BYTES) { "Picker import limit must be between 1 byte and 8 GiB" }
        val mime = when (type) {
            1L -> "image/*"
            2L -> "video/*"
            3L -> "audio/*"
            else -> "*/*"
        }
        val intent = visualMediaIntent(type, limit) ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mime
            if (type == 5L) {
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.launchForResult(intent) { result, data ->
            if (result != Activity.RESULT_OK || data == null) {
                completion.complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(mapOf("items" to WireValue.Text("[]"))),
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
            executor.execute { importUris(uris, completion, maximumBytes) }
        }
    }

    private fun importContentUri(payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val raw = WireMap.decode(payload).requiredText("uri")
            val uri = Uri.parse(raw)
            require(uri.scheme == "content") { "Only content URIs can be imported" }
            val imported = importUri(uri)
            completion.success(imported.file, imported.mime, imported.name)
        }.onFailure { completion.failure(it) }
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
                completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                return@launchForResult
            }
            executor.execute {
                importUri(output, completion)
                activity.contentResolver.delete(output, null, null)
            }
        }
    }

    private fun importUri(uri: Uri, completion: ModuleCompletion, maximumBytes: Long = MAX_IMPORT_BYTES) {
        runCatching {
            val imported = importUri(uri, maximumBytes)
            completion.success(imported.file, imported.mime, imported.name)
        }.onFailure { completion.failure(it) }
    }

    private fun importUris(uris: List<Uri>, completion: ModuleCompletion, maximumBytes: Long = MAX_IMPORT_BYTES) {
        val imported = mutableListOf<ImportedFile>()
        // Each file may use the per-file limit; the selection may use at least
        // 256 MiB and never more than the 8 GiB picker ceiling.
        val selectionLimit = multiImportLimit(maximumBytes)
        runCatching {
            var total = 0L
            uris.forEach { uri ->
                val remaining = selectionLimit - total
                require(remaining > 0) { "Selected files exceed ${selectionLimit / (1024L * 1024L)} MiB" }
                val item = importUri(uri, minOf(maximumBytes, remaining))
                imported += item
                total += item.file.length()
            }
            val items = JSONArray()
            imported.forEach { item ->
                items.put(JSONObject().apply {
                    put("path", item.file.relativeTo(root).path)
                    put("name", item.name)
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
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    val reportedSize = cursor.getLong(sizeIndex)
                    require(reportedSize < 0 || reportedSize <= maximumBytes) {
                        "Selected file exceeds ${maximumBytes / 1_048_576} MiB"
                    }
                }
            }
        }
        val file = uniqueImport(name)
        return try {
            activity.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to read selected file" }
                file.outputStream().use { output ->
                    copyImportedDocument(input, output, maximumBytes)
                }
            }
            ImportedFile(file, mime, name)
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

    private fun ModuleCompletion.success(file: File, mime: String, displayName: String = file.name) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(
                mapOf(
                    "path" to WireValue.Text(relativeSandboxPath(root, file)),
                    "name" to WireValue.Text(displayName),
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
        downloads.keys.toList().forEach { id ->
            downloads.remove(id)?.let { task ->
                task.future?.cancel(true)
                task.channel.close()
            }
        }
        downloadExecutor.shutdownNow()
        executor.shutdown()
    }

    private companion object {
        const val DOWNLOAD_PROGRESS = 1L
        const val DOWNLOAD_COMPLETE = 2L
        const val DOWNLOAD_FAILED = 3L
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")
        val BLOCKED_HEADERS = setOf("connection", "content-length", "host", "transfer-encoding")
        const val DEFAULT_PICK_LIMIT = 10
        const val MAX_PICK_LIMIT = 50
        const val MAX_READ_BYTES = 1024 * 1024L
        const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L
        const val MAX_PICK_IMPORT_BYTES = 8L * 1024L * 1024L * 1024L
        val PICKER_MIME = Regex("^[a-zA-Z0-9!#\\$&^_.+-]+/[a-zA-Z0-9!#\\$&^_.+*-]+$")
        const val MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L
        const val MAX_MULTI_IMPORT_BYTES = 256L * 1024L * 1024L
        const val MAX_SHARE_FILES = 50
    }

    private data class DownloadTask(
        val channel: WatchChannel,
        @Volatile var future: Future<*>? = null,
    )

    private data class ImportedFile(val file: File, val mime: String, val name: String)
}

internal fun copyImportedDocument(input: InputStream, output: OutputStream, maximumBytes: Long): Long {
    require(maximumBytes > 0) { "Picker import limit must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(read <= maximumBytes - total) {
            "Selected file exceeds ${maximumBytes / 1_048_576} MiB"
        }
        output.write(buffer, 0, read)
        total += read
    }
    return total
}

internal fun normalizedBundledAssetPath(path: String): String {
    val normalized = path.trim().replace('\\', '/')
    require(normalized.isNotEmpty() && !normalized.startsWith('/') && normalized.length <= 1_024) {
        "Invalid bundled asset path"
    }
    require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Bundled asset path escapes application bundle"
    }
    return normalized
}

internal fun relativeSandboxPath(root: File, file: File): String {
    val canonicalRoot = root.canonicalFile
    val canonicalFile = file.canonicalFile
    require(canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
        "File path escapes sandbox"
    }
    return canonicalFile.relativeTo(canonicalRoot).path
}

/** Moves or copies one private sandbox entry; copies are staged and published atomically. */
internal fun transferPrivateFile(source: File, target: File, move: Boolean, overwrite: Boolean) {
    require(source.exists()) { "Source does not exist" }
    require(source.canonicalPath != target.canonicalPath) { "Source and destination are the same" }
    require(move || source.isFile) { "Only files can be copied" }
    require(!target.path.startsWith(source.path + File.separator)) {
        "Cannot move a directory inside itself"
    }
    if (target.exists()) {
        require(overwrite) { "Destination already exists" }
        require(target.isFile && source.isFile) { "Only files can replace an existing destination" }
    }
    val parent = target.parentFile ?: error("Destination has no parent directory")
    require(parent.isDirectory || parent.mkdirs()) { "Unable to create destination directory" }
    if (move) {
        val options = if (overwrite) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        runCatching { Files.move(source.toPath(), target.toPath(), *options) }.getOrElse {
            if (overwrite) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(source.toPath(), target.toPath())
            }
        }
        return
    }
    val temporary = File(parent, ".${target.name}.tmp-${UUID.randomUUID()}")
    try {
        source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        require(temporary.length() == source.length()) { "Source changed during copy" }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        temporary.delete()
    }
}

/** Returns the narrowest MIME type that describes every shared file. */
internal fun sharedMimeType(types: List<String>): String {
    val normalized = types.map { it.lowercase().substringBefore(';').trim() }.filter { '/' in it }
    if (normalized.isEmpty() || normalized.size != types.size) return "*/*"
    if (normalized.distinct().size == 1) return normalized.first()
    val families = normalized.map { it.substringBefore('/') }.distinct()
    return if (families.size == 1) "${families.first()}/*" else "*/*"
}

internal fun shareFilesIntent(uris: List<Uri>, mime: String): Intent {
    require(uris.isNotEmpty()) { "At least one file is required" }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    intent.clipData = android.content.ClipData.newRawUri(null, uris.first()).apply {
        uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
}

/** Total bytes a multi-file pick may import for a per-file [maximumBytes]. */
internal fun multiImportLimit(maximumBytes: Long): Long =
    maxOf(256L * 1024L * 1024L, maximumBytes).coerceAtMost(8L * 1024L * 1024L * 1024L)
