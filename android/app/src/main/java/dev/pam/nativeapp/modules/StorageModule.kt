package dev.pam.nativeapp.modules

import android.content.Context
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class StorageModule(context: Context) : NativeModule, AutoCloseable {
    private val preferences = context.getSharedPreferences("pam-native", Context.MODE_PRIVATE)
    private val closed = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-storage").apply { isDaemon = true }
    }

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (closed.get()) {
            completion.complete(ModuleResultStatus.FAILURE, "Storage module is closed".toByteArray())
            return
        }
        try {
            executor.execute {
                runCatching {
                    val values = WireMap.decode(payload)
                    val key = (values["key"] as? WireValue.Text)?.value
                        ?: error("Storage key is required")
                    require(key.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) { "Invalid storage key" }
                    when (method) {
                        "get" -> {
                            val value = preferences.getString(key, null)
                            WireMap.encode(
                                value?.let { mapOf("value" to WireValue.Text(it)) } ?: emptyMap(),
                            )
                        }
                        "set" -> {
                            val value = (values["value"] as? WireValue.Text)?.value
                                ?: error("Storage value is required")
                            require(value.toByteArray().size <= MAX_STORAGE_VALUE_BYTES) {
                                "Storage value exceeds 256 KiB"
                            }
                            check(preferences.edit().putString(key, value).commit()) {
                                "Storage write failed"
                            }
                            WireMap.encode(emptyMap())
                        }
                        else -> error("Unknown storage method")
                    }
                }.fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = {
                        completion.complete(
                            ModuleResultStatus.FAILURE,
                            (it.message ?: "Storage operation failed").toByteArray(),
                        )
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            completion.complete(ModuleResultStatus.FAILURE, "Storage module is closed".toByteArray())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_STORAGE_VALUE_BYTES = 256 * 1024
    }
}
