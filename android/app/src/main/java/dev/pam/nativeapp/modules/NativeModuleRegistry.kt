package dev.pam.nativeapp.modules

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

class NativeModuleRegistry(context: Context) : AutoCloseable {
    private val generation = AtomicLong(1)
    private val http = HttpModule()
    private val storage = StorageModule(context)
    private val system = SystemModule(context)
    private val sqlite = SQLiteModule(context)
    private val files = (context as? dev.pam.nativeapp.PamActivity)?.let(::FilesModule)
    private val notifications =
        (context as? dev.pam.nativeapp.PamActivity)?.let(::NotificationsModule)
    private val linking = LinkingModule()
    private val incomingShare = IncomingShareModule()
    private val cache = CacheModule(context)
    private val background = BackgroundModule(context)
    private val device = DeviceModule(context)
    private val permissions =
        (context as? dev.pam.nativeapp.PamActivity)?.let(::PermissionsModule)
    private val sensors = SensorsModule(context)
    private val contacts = ContactsModule(context)
    private val sms = (context as? android.app.Activity)?.let(::SmsModule)
    private val mediaLibrary = MediaLibraryModule(context)
    private val location = LocationModule(context)
    private val audioRecorder = AudioRecorderModule(context)
    private val imageEditor = ImageEditorModule(context)
    private val timers = TimersModule()
    private val modules: Map<String, NativeModule> = buildMap {
        put("http", http)
        put("storage", storage)
        put("sqlite", sqlite)
        files?.let { put("files", it) }
        notifications?.let { put("notifications", it) }
        put("linking", linking)
        put("incoming-share", incomingShare)
        put("cache", cache)
        put("background", background)
        put("device", device)
        permissions?.let { put("permissions", it) }
        put("sensors", sensors)
        put("contacts", contacts)
        sms?.let { put("sms", it) }
        put("media-library", mediaLibrary)
        put("location", location)
        put("audio-recorder", audioRecorder)
        put("image-editor", imageEditor)
        put("timers", timers)
        putAll(GeneratedPamModules.create(context))
    }

    fun invoke(
        operationValue: Int,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        val reloadSafeCompletion = completion.forGeneration(generation.get())
        when (val operation = NativeOperation.from(operationValue)) {
            NativeOperation.HTTP_GET -> http.invoke("get", payload, reloadSafeCompletion)
            NativeOperation.STORAGE_GET -> storage.invoke("get", payload, reloadSafeCompletion)
            NativeOperation.STORAGE_SET -> storage.invoke("set", payload, reloadSafeCompletion)
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
            NativeOperation.HAPTIC,
            NativeOperation.CLIPBOARD_SET_TEXT,
            NativeOperation.CLIPBOARD_GET_TEXT,
            NativeOperation.CLIPBOARD_HAS_TEXT,
            NativeOperation.SENSOR_READ,
            -> system.invoke(operation, payload, reloadSafeCompletion)
            null -> reloadSafeCompletion.complete(
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
        val reloadSafeCompletion = completion.forGeneration(generation.get())
        val implementation = modules[module]
        if (implementation == null) {
            reloadSafeCompletion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown native module $module".toByteArray(),
            )
            return
        }
        implementation.invoke(method, payload, reloadSafeCompletion)
    }

    fun prepareReload() {
        generation.incrementAndGet()
        PamDeepLinks.prepareReload()
        PamIncomingShares.prepareReload()
        PamPushNotifications.prepareReload()
    }

    override fun close() {
        generation.incrementAndGet()
        modules.values.filterIsInstance<AutoCloseable>().forEach {
            runCatching { it.close() }
        }
        runCatching { system.close() }
    }

    private fun ModuleCompletion.forGeneration(expected: Long): ModuleCompletion =
        ModuleCompletion { status, payload ->
            if (generation.get() == expected) {
                complete(status, payload)
            }
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
    CLOSE_APP(14),
    HAPTIC(15),
    CLIPBOARD_SET_TEXT(16),
    CLIPBOARD_GET_TEXT(17),
    CLIPBOARD_HAS_TEXT(18),
    SENSOR_READ(19);

    companion object {
        fun from(value: Int): NativeOperation? =
            entries.firstOrNull { operation -> operation.value == value }
    }
}
