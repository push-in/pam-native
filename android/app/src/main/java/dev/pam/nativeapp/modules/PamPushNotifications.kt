package dev.pam.nativeapp.modules

import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.ArrayDeque
import org.json.JSONObject

public object PamPushNotifications {
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var waiter: ModuleCompletion? = null

    @JvmStatic
    public fun reportReceived(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        report(1, id, title, body, dataJson, deepLink)
    }

    @JvmStatic
    public fun reportOpened(
        id: String,
        title: String = "",
        body: String = "",
        dataJson: String = "{}",
        deepLink: String = "",
    ) {
        report(2, id, title, body, dataJson, deepLink)
    }

    internal fun next(completion: ModuleCompletion) {
        val event = synchronized(lock) {
            require(waiter == null) { "Only one push listener can wait at a time" }
            if (events.isEmpty()) {
                waiter = completion
                null
            } else {
                events.removeFirst()
            }
        }
        if (event != null) completion.complete(ModuleResultStatus.SUCCESS, event)
    }

    internal fun close(message: String) {
        val pending = synchronized(lock) {
            val value = waiter
            waiter = null
            value
        }
        pending?.complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    internal fun prepareReload() {
        synchronized(lock) {
            waiter = null
        }
    }

    private fun report(
        event: Int,
        id: String,
        title: String,
        body: String,
        dataJson: String,
        deepLink: String,
    ) {
        val payload = WireMap.encode(
            mapOf(
                "event" to WireValue.Integer(event.toLong()),
                "id" to WireValue.Text(id.take(512)),
                "title" to WireValue.Text(title.take(4_096)),
                "body" to WireValue.Text(body.take(16_384)),
                "data" to WireValue.Text(
                    runCatching { JSONObject(dataJson.take(MAX_DATA_BYTES)).toString() }
                        .getOrDefault("{}"),
                ),
                "deepLink" to WireValue.Text(deepLink.take(8_192)),
            ),
        )
        val pending = synchronized(lock) {
            val value = waiter
            if (value == null) {
                if (events.size >= MAX_QUEUED_EVENTS) events.removeFirst()
                events.addLast(payload)
            } else {
                waiter = null
            }
            value
        }
        pending?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    private const val MAX_QUEUED_EVENTS = 64
    private const val MAX_DATA_BYTES = 256 * 1024
}
