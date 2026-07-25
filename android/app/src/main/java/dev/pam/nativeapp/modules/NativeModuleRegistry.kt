package dev.pam.nativeapp.modules

import android.content.Context

class NativeModuleRegistry(context: Context) : AutoCloseable {
    private val http = HttpModule()
    private val storage = StorageModule(context)
    private val system = SystemModule(context)
    private val modules: Map<String, NativeModule> = buildMap {
        put("http", http)
        put("storage", storage)
        putAll(GeneratedPamModules.create(context))
    }

    fun invoke(
        operationValue: Int,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        when (val operation = NativeOperation.from(operationValue)) {
            NativeOperation.HTTP_GET -> http.invoke("get", payload, completion)
            NativeOperation.STORAGE_GET -> storage.invoke("get", payload, completion)
            NativeOperation.STORAGE_SET -> storage.invoke("set", payload, completion)
            NativeOperation.ALERT,
            NativeOperation.TOAST,
            NativeOperation.SHARE,
            NativeOperation.OPEN_URL,
            NativeOperation.CAN_OPEN_URL,
            NativeOperation.VIBRATE,
            NativeOperation.DEVICE_INFO,
            NativeOperation.KEYBOARD_DISMISS,
            NativeOperation.PERMISSION_CHECK,
            NativeOperation.PERMISSION_REQUEST,
            NativeOperation.CLOSE_APP,
            -> system.invoke(operation, payload, completion)
            null -> completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown native operation $operationValue".toByteArray(),
            )
        }
    }

    fun invoke(
        module: String,
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        val implementation = modules[module]
        if (implementation == null) {
            completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown native module $module".toByteArray(),
            )
            return
        }
        implementation.invoke(method, payload, completion)
    }

    override fun close() {
        modules.values.filterIsInstance<AutoCloseable>().forEach {
            runCatching { it.close() }
        }
        runCatching { system.close() }
    }
}

enum class NativeOperation(val value: Int) {
    HTTP_GET(1),
    STORAGE_GET(2),
    STORAGE_SET(3),
    ALERT(4),
    TOAST(5),
    SHARE(6),
    OPEN_URL(7),
    CAN_OPEN_URL(8),
    VIBRATE(9),
    DEVICE_INFO(10),
    KEYBOARD_DISMISS(11),
    PERMISSION_CHECK(12),
    PERMISSION_REQUEST(13),
    CLOSE_APP(14);

    companion object {
        fun from(value: Int): NativeOperation? =
            entries.firstOrNull { operation -> operation.value == value }
    }
}
