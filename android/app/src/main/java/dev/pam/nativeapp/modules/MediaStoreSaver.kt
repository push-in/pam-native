package dev.pam.nativeapp.modules

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import org.json.JSONObject

/**
 * Publishes a private file into the shared photo/video library.
 *
 * Android 10+ uses scoped MediaStore inserts that need no permission. Android
 * 8-9 writes into the public Pictures/Movies directory, which requires the
 * caller to hold WRITE_EXTERNAL_STORAGE (declared with maxSdkVersion 28).
 */
internal class MediaStoreSaver(private val context: Context) {
    fun save(source: File, mimeType: String, album: String?, displayName: String): JSONObject {
        require(source.isFile) { "File does not exist" }
        val mime = mimeType.lowercase()
        val video = mime.startsWith("video/")
        require(video || mime.startsWith("image/")) { "Only images and videos can be saved to the media library" }
        val folder = normalizedAlbum(album)
        val name = normalizedDisplayName(displayName)
        val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1_000)
        }
        val collection: Uri
        var legacyTarget: File? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (folder == null) directory else "$directory/$folder",
            )
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            collection = if (video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            @Suppress("DEPRECATION")
            val publicDirectory = File(Environment.getExternalStoragePublicDirectory(directory), folder.orEmpty())
            require(publicDirectory.isDirectory || publicDirectory.mkdirs()) {
                "Cannot create the public media directory"
            }
            val target = uniqueFile(publicDirectory, name)
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            legacyTarget = target
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
            @Suppress("DEPRECATION")
            values.put(MediaStore.MediaColumns.DATA, target.absolutePath)
            values.put(MediaStore.MediaColumns.SIZE, target.length())
        }
        val uri = try {
            resolver.insert(collection, values) ?: error("The media library rejected the file")
        } catch (error: Throwable) {
            legacyTarget?.delete()
            throw error
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val output = resolver.openOutputStream(uri) ?: error("Cannot open the media library entry")
                output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        val id = ContentUris.parseId(uri)
        return JSONObject().apply {
            put("id", id.toString())
            put("uri", uri.toString())
            put("name", legacyTarget?.name ?: name)
            put("mimeType", mime)
            put("size", source.length())
            put("createdAt", System.currentTimeMillis())
            put("modifiedAt", System.currentTimeMillis())
            put("albumTitle", folder ?: directory)
        }
    }

    internal companion object {
        private val ALBUM = Regex("^[\\p{L}\\p{N} _.()'-]{1,64}$")

        fun normalizedAlbum(album: String?): String? {
            val value = album?.trim().orEmpty()
            if (value.isEmpty()) return null
            require(ALBUM.matches(value) && value != "." && value != "..") {
                "Album names may contain letters, numbers, spaces and . _ - ( ) ' (max 64)"
            }
            return value
        }

        fun normalizedDisplayName(name: String): String {
            val value = name.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(128)
            require(value.isNotEmpty() && value != "." && value != "..") { "Invalid media file name" }
            return value
        }

        private fun uniqueFile(directory: File, name: String): File {
            var candidate = File(directory, name)
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            var counter = 1
            while (candidate.exists()) {
                candidate = File(directory, "$base ($counter)$extension")
                counter++
            }
            return candidate
        }
    }
}
