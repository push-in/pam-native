package dev.pam.nativeapp.modules

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
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
                when (method) {
                    "execute" -> {
                        val arguments = decodeArguments(values.requiredText("arguments"))
                        database.execSQL(sql, arguments)
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                    }
                    "query" -> {
                        val arguments = decodeArguments(values.requiredText("arguments"))
                        val rows = database.rawQuery(sql, arguments.map(::stringArgument).toTypedArray())
                            .use(::encodeRows)
                        completion.complete(
                            ModuleResultStatus.SUCCESS,
                            WireMap.encode(mapOf("rows" to WireValue.Text(rows.toString()))),
                        )
                    }
                    "executeMany" -> {
                        executeMany(
                            database,
                            sql,
                            decodeArgumentSets(values.requiredText("arguments")),
                        )
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                    }
                    "transaction" -> {
                        executeTransaction(
                            database,
                            decodeStatements(values.requiredText("arguments")),
                        )
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
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
            SQLiteDatabase.openOrCreateDatabase(File(root, name), null).apply {
                enableWriteAheadLogging()
                setForeignKeyConstraintsEnabled(true)
                pragma(this, "synchronous=NORMAL")
                pragma(this, "busy_timeout=5000")
                pragma(this, "temp_store=MEMORY")
            }
        }

    private fun pragma(database: SQLiteDatabase, expression: String) {
        database.rawQuery("PRAGMA $expression", emptyArray()).use { cursor ->
            cursor.moveToFirst()
        }
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

    private fun decodeArgumentSets(value: String): List<Array<Any?>> {
        val batches = JSONArray(value)
        require(batches.length() in 1..MAX_BATCH_ROWS) {
            "SQLite executeMany requires between 1 and 10000 argument sets"
        }
        return List(batches.length()) { index ->
            decodeArguments(batches.getJSONArray(index).toString())
        }
    }

    private fun executeMany(
        database: SQLiteDatabase,
        sql: String,
        argumentSets: List<Array<Any?>>,
    ) {
        val statement = database.compileStatement(sql)
        database.beginTransactionNonExclusive()
        try {
            argumentSets.forEach { arguments ->
                statement.clearBindings()
                bind(arguments, statement)
                statement.execute()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            statement.close()
        }
    }

    private fun decodeStatements(value: String): List<Pair<String, Array<Any?>>> {
        val statements = JSONArray(value)
        require(statements.length() in 1..MAX_BATCH_ROWS) {
            "SQLite transaction requires between 1 and 10000 statements"
        }
        return List(statements.length()) { index ->
            val statement = statements.getJSONObject(index)
            val sql = statement.getString("sql")
            require(sql.isNotEmpty() && sql.toByteArray().size <= MAX_SQL_BYTES) {
                "Invalid SQLite transaction SQL statement"
            }
            sql to decodeArguments(statement.getJSONArray("arguments").toString())
        }
    }

    private fun executeTransaction(
        database: SQLiteDatabase,
        statements: List<Pair<String, Array<Any?>>>,
    ) {
        database.beginTransactionNonExclusive()
        try {
            statements.forEach { (sql, arguments) ->
                database.compileStatement(sql).use { statement ->
                    bind(arguments, statement)
                    statement.execute()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun bind(arguments: Array<Any?>, statement: SQLiteStatement) {
        arguments.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                null -> statement.bindNull(index)
                is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
                is Byte, is Short, is Int, is Long ->
                    statement.bindLong(index, (value as Number).toLong())
                is Float, is Double -> statement.bindDouble(index, (value as Number).toDouble())
                is String -> statement.bindString(index, value)
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
        const val MAX_BATCH_ROWS = 10_000
        const val MAX_SQL_BYTES = 1_048_576
    }
}
