package dev.pam.nativeapp.modules

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class MediaLibraryModule(private val context: Context) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-media-library").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        if (method != "assets" && method != "albums") {
            completion.failure("Unknown media-library method $method")
            return
        }
        if (!hasMediaPermission()) {
            completion.failure("Photos permission is not granted")
            return
        }
        try {
            executor.execute {
                runCatching {
                    when (method) {
                        "assets" -> assets(payload)
                        else -> albums(payload)
                    }
                }.fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = {
                        completion.failure(it.message ?: "Cannot read the media library")
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            completion.failure("Media-library module is closed")
        }
    }

    private fun assets(payload: ByteArray): ByteArray {
        check(!closed.get()) { "Media-library module is closed" }
        val values = WireMap.decode(payload)
        val type = values.integer("type", TYPE_MEDIA)
        val offset = values.integer("offset", 0L).toInt().coerceAtLeast(0)
        val limit = values.integer("limit", DEFAULT_PAGE_SIZE.toLong())
            .toInt()
            .coerceIn(1, MAX_PAGE_SIZE)
        val albumId = values.text("albumId").orEmpty()
        val (selection, arguments) = selection(type, albumId)
        val rows = JSONArray()
        val query = Bundle().apply {
            putString(ContentResolverArgs.SQL_SELECTION, selection)
            putStringArray(ContentResolverArgs.SQL_SELECTION_ARGS, arguments)
            putStringArray(
                ContentResolverArgs.SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID),
            )
            putInt(ContentResolverArgs.SORT_DIRECTION, ContentResolverArgs.SORT_DESCENDING)
            putInt(ContentResolverArgs.LIMIT, limit + 1)
            putInt(ContentResolverArgs.OFFSET, offset)
        }
        context.contentResolver.query(
            collection(),
            assetProjection(),
            query,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.length() < limit + 1) {
                rows.put(asset(cursor))
            }
        }
        val hasMore = rows.length() > limit
        if (hasMore) {
            rows.remove(rows.length() - 1)
        }
        return WireMap.encode(mapOf(
            "items" to WireValue.Text(rows.toString()),
            "hasMore" to WireValue.Flag(hasMore),
        ))
    }

    private fun albums(payload: ByteArray): ByteArray {
        check(!closed.get()) { "Media-library module is closed" }
        val type = WireMap.decode(payload).integer("type", TYPE_MEDIA)
        val (selection, arguments) = selection(type, "")
        val albums = linkedMapOf<String, AlbumRow>()
        val query = Bundle().apply {
            putString(ContentResolverArgs.SQL_SELECTION, selection)
            putStringArray(ContentResolverArgs.SQL_SELECTION_ARGS, arguments)
            putStringArray(
                ContentResolverArgs.SORT_COLUMNS,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns._ID),
            )
            putInt(ContentResolverArgs.SORT_DIRECTION, ContentResolverArgs.SORT_DESCENDING)
        }
        context.contentResolver.query(
            collection(),
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                BUCKET_ID,
                BUCKET_NAME,
            ),
            query,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.long(MediaStore.MediaColumns._ID).toString()
                val bucketId = cursor.string(BUCKET_ID).ifBlank { "uncategorized" }
                val title = cursor.string(BUCKET_NAME).ifBlank { "Sem álbum" }
                val row = albums[bucketId]
                if (row == null) {
                    albums[bucketId] = AlbumRow(
                        id = bucketId,
                        title = title,
                        count = 1,
                        coverUri = ContentUris.withAppendedId(collection(), id.toLong()).toString(),
                    )
                } else {
                    row.count++
                }
            }
        }
        val items = JSONArray()
        albums.values.forEach { row ->
            items.put(JSONObject().apply {
                put("id", row.id)
                put("title", row.title)
                put("count", row.count)
                put("coverUri", row.coverUri)
            })
        }
        return WireMap.encode(mapOf("items" to WireValue.Text(items.toString())))
    }

    private fun asset(cursor: Cursor): JSONObject {
        val id = cursor.long(MediaStore.MediaColumns._ID)
        val mimeType = cursor.string(MediaStore.MediaColumns.MIME_TYPE)
        val createdAt = cursor.long(MediaStore.MediaColumns.DATE_ADDED).coerceAtLeast(0L) * 1_000L
        val modifiedAt = cursor.long(MediaStore.MediaColumns.DATE_MODIFIED).coerceAtLeast(0L) * 1_000L
        return JSONObject().apply {
            put("id", id.toString())
            put("uri", ContentUris.withAppendedId(collection(), id).toString())
            put("name", cursor.string(MediaStore.MediaColumns.DISPLAY_NAME))
            put("mimeType", mimeType)
            put("width", cursor.int(MediaStore.MediaColumns.WIDTH).coerceAtLeast(0))
            put("height", cursor.int(MediaStore.MediaColumns.HEIGHT).coerceAtLeast(0))
            put("durationMs", cursor.long(MediaStore.Video.VideoColumns.DURATION).coerceAtLeast(0L))
            put("size", cursor.long(MediaStore.MediaColumns.SIZE).coerceAtLeast(0L))
            put("createdAt", createdAt)
            put("modifiedAt", modifiedAt)
            put("albumId", cursor.string(BUCKET_ID))
            put("albumTitle", cursor.string(BUCKET_NAME))
            put("favorite", cursor.optionalInt(MediaStore.MediaColumns.IS_FAVORITE) == 1)
        }
    }

    private fun assetProjection(): Array<String> = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.DISPLAY_NAME)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.Video.VideoColumns.DURATION)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(BUCKET_ID)
        add(BUCKET_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.IS_FAVORITE)
        }
    }.toTypedArray()

    private fun selection(type: Long, albumId: String): Pair<String, Array<String>> {
        val mediaTypes = when (type) {
            TYPE_IMAGE -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            TYPE_VIDEO -> listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            else -> listOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            )
        }
        val clauses = mutableListOf(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString(",") { "?" }})",
            "${MediaStore.MediaColumns.SIZE} > 0",
        )
        val arguments = mediaTypes.map(Int::toString).toMutableList()
        if (albumId.isNotBlank()) {
            clauses += "$BUCKET_ID = ?"
            arguments += albumId
        }
        return clauses.joinToString(" AND ") to arguments.toTypedArray()
    }

    private fun collection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

    private fun hasMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        }
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Cursor.index(column: String): Int =
        getColumnIndex(column).takeIf { it >= 0 } ?: error("Missing media column $column")

    private fun Cursor.string(column: String): String =
        getString(index(column)).orEmpty()

    private fun Cursor.long(column: String): Long =
        getLong(index(column))

    private fun Cursor.int(column: String): Int =
        getInt(index(column))

    private fun Cursor.optionalInt(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
        (this[key] as? WireValue.Integer)?.value ?: fallback

    private fun Map<String, WireValue>.text(key: String): String? =
        (this[key] as? WireValue.Text)?.value

    private fun ModuleCompletion.failure(message: String) {
        complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow()
        }
    }

    private data class AlbumRow(
        val id: String,
        val title: String,
        var count: Int,
        val coverUri: String,
    )

    private companion object {
        const val BUCKET_ID = "bucket_id"
        const val BUCKET_NAME = "bucket_display_name"
        const val DEFAULT_PAGE_SIZE = 80
        const val MAX_PAGE_SIZE = 200
        const val TYPE_IMAGE = 1L
        const val TYPE_VIDEO = 2L
        const val TYPE_MEDIA = 5L
    }

    private object ContentResolverArgs {
        const val SQL_SELECTION = "android:query-arg-sql-selection"
        const val SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args"
        const val SORT_COLUMNS = "android:query-arg-sort-columns"
        const val SORT_DIRECTION = "android:query-arg-sort-direction"
        const val SORT_DESCENDING = 1
        const val LIMIT = "android:query-arg-limit"
        const val OFFSET = "android:query-arg-offset"
    }
}
