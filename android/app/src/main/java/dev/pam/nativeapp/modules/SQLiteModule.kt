package dev.pam.nativeapp.modules

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal class SQLiteModule(private val context: Context) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val databases = mutableMapOf<String, SQLiteDatabase>()

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        executor.execute {
            runCatching {
                val values = WireMap.decode(payload)
                val database = open(values.requiredText("database"))
                val sql = values.requiredText("sql")
                val arguments = decodeArguments(values.requiredText("arguments"))
                when (method) {
                    "execute" -> {
                        database.execSQL(sql, arguments)
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                    }
                    "query" -> {
                        val rows = database.rawQuery(sql, arguments.map(::stringArgument).toTypedArray())
                            .use(::encodeRows)
                        completion.complete(
                            ModuleResultStatus.SUCCESS,
                            WireMap.encode(mapOf("rows" to WireValue.Text(rows.toString()))),
                        )
                    }
                    else -> error("Unknown SQLite method $method")
                }
            }.onFailure { error ->
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    (error.message ?: "SQLite operation failed").toByteArray(),
                )
            }
        }
    }

    private fun open(name: String): SQLiteDatabase =
        databases.getOrPut(name) {
            val root = File(context.filesDir, "pam-databases").apply { mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(File(root, name), null)
        }

    private fun decodeArguments(value: String): Array<Any?> {
        val array = JSONArray(value)
        return Array(array.length()) { index ->
            when (val item = array.opt(index)) {
                JSONObject.NULL -> null
                is Boolean -> if (item) 1L else 0L
                is String, is Number -> item
                else -> error("SQLite arguments must be scalar")
            }
        }
    }

    private fun stringArgument(value: Any?): String =
        when (value) {
            null -> ""
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }

    private fun encodeRows(cursor: Cursor): JSONArray =
        JSONArray().apply {
            while (cursor.moveToNext()) {
                require(length() < MAX_QUERY_ROWS) {
                    "SQLite query exceeded the 1000-row bridge limit; paginate the query"
                }
                require(cursor.columnCount <= MAX_QUERY_COLUMNS) {
                    "SQLite query exceeded the 256-column bridge limit"
                }
                put(JSONObject().apply {
                    repeat(cursor.columnCount) { index ->
                        put(
                            cursor.getColumnName(index),
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                Cursor.FIELD_TYPE_BLOB ->
                                    android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP)
                                else -> cursor.getString(index)
                            },
                        )
                    }
                })
            }
        }

    override fun close() {
        executor.shutdown()
        databases.values.forEach(SQLiteDatabase::close)
        databases.clear()
    }

    private fun Map<String, WireValue>.requiredText(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: error("Missing text field $key")

    private companion object {
        const val MAX_QUERY_ROWS = 1_000
        const val MAX_QUERY_COLUMNS = 256
    }
}
