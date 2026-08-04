package dev.pam.nativeapp.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class ContactsModule(private val context: Context) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-contacts").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        if (method != "list") {
            completion.complete(ModuleResultStatus.FAILURE, "Unknown contacts method $method".toByteArray())
            return
        }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            completion.complete(ModuleResultStatus.FAILURE, "Contacts permission is not granted".toByteArray())
            return
        }
        try {
            executor.execute {
                runCatching { list(payload) }.fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = {
                        completion.complete(
                            ModuleResultStatus.FAILURE,
                            (it.message ?: "Cannot read contacts").toByteArray(),
                        )
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            completion.complete(ModuleResultStatus.FAILURE, "Contacts module is closed".toByteArray())
        }
    }

    private fun list(payload: ByteArray): ByteArray {
        check(!closed.get()) { "Contacts module is closed" }
        val values = WireMap.decode(payload)
        val offset = ((values["offset"] as? WireValue.Integer)?.value ?: 0).toInt().coerceAtLeast(0)
        val limit = ((values["limit"] as? WireValue.Integer)?.value ?: 250).toInt().coerceIn(1, 250)
        val rows = mutableListOf<ContactRow>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { cursor ->
            if (cursor.moveToOffset(offset)) {
                do {
                    rows += ContactRow(
                        id = cursor.getString(0),
                        displayName = cursor.getString(1).orEmpty(),
                    )
                } while (rows.size < limit + 1 && cursor.moveToNext())
            }
        }
        val hasMore = rows.size > limit
        if (hasMore) rows.removeAt(rows.lastIndex)
        populateDetails(rows)
        val items = JSONArray()
        rows.forEach { row ->
            items.put(JSONObject().apply {
                put("id", row.id)
                put("displayName", row.displayName)
                put("givenName", row.givenName)
                put("familyName", row.familyName)
                put("phoneNumbers", JSONArray(row.phoneNumbers.distinct()))
                put("emailAddresses", JSONArray(row.emailAddresses.distinct()))
            })
        }
        return WireMap.encode(mapOf(
            "items" to WireValue.Text(items.toString()),
            "hasMore" to WireValue.Flag(hasMore),
        ))
    }

    private fun populateDetails(rows: List<ContactRow>) {
        if (rows.isEmpty()) return
        val byId = rows.associateBy(ContactRow::id)
        val placeholders = rows.joinToString(",") { "?" }
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ),
            "${ContactsContract.Data.CONTACT_ID} IN ($placeholders) AND ${ContactsContract.Data.MIMETYPE} IN (?,?,?)",
            rows.map(ContactRow::id).toTypedArray() + arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = byId[cursor.getString(0)] ?: continue
                when (cursor.getString(1)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        row.givenName = cursor.getString(3).orEmpty()
                        row.familyName = cursor.getString(4).orEmpty()
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        cursor.getString(2)?.takeIf(String::isNotBlank)?.let(row.phoneNumbers::add)
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        cursor.getString(2)?.takeIf(String::isNotBlank)?.let(row.emailAddresses::add)
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }

    private data class ContactRow(
        val id: String,
        val displayName: String,
        var givenName: String = "",
        var familyName: String = "",
        val phoneNumbers: MutableList<String> = mutableListOf(),
        val emailAddresses: MutableList<String> = mutableListOf(),
    )
}

internal fun Cursor.moveToOffset(offset: Int): Boolean =
    offset >= 0 && offset < count && moveToPosition(offset)
