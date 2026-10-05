package dev.pam.nativeapp.modules

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dev.pam.nativeapp.BuildConfig
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object NotificationActionType {
    const val REPLY = 1
    const val MARK_READ = 2
}

/**
 * Notification action pipeline: optional native HTTP delivery (so a reply is
 * sent even when PHP is not running), conversation bookkeeping, then a durable
 * queue drained by Notifications::onAction() in PHP.
 */
public object PamNotificationActions {
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var waiter: ModuleCompletion? = null
    private var preferences: SharedPreferences? = null

    internal fun handle(context: Context, type: Int, key: String, text: String) {
        attach(context)
        val spec = PamConversationNotifications.load(context, key)
        if (type == NotificationActionType.REPLY) {
            PamConversationNotifications.appendOwnReply(context, key, text)
        } else {
            PamConversationNotifications.cancel(context, key)
        }
        val endpoint = if (type == NotificationActionType.REPLY) spec?.replyEndpoint else spec?.markReadEndpoint
        val variables = NotificationTemplate.variables(spec?.dataJson ?: "{}") + mapOf(
            "reply" to text,
            "conversation" to key,
        )
        val status = endpoint?.let {
            runCatching { NotificationEndpoint.send(context, it, variables) }.getOrDefault(0)
        } ?: -1
        report(type, key, text, spec?.dataJson ?: "{}", spec?.deepLink.orEmpty(), status)
    }

    @JvmStatic
    public fun attach(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val stored = runCatching {
                JSONArray(preferences?.getString(PREFERENCES_EVENTS, "[]") ?: "[]")
            }.getOrDefault(JSONArray())
            for (index in 0 until stored.length()) {
                runCatching { Base64.decode(stored.optString(index), Base64.NO_WRAP) }
                    .getOrNull()
                    ?.takeIf(ByteArray::isNotEmpty)
                    ?.let(events::addLast)
            }
            while (events.size > MAX_QUEUED_EVENTS) events.removeFirst()
        }
    }

    internal fun next(completion: ModuleCompletion) {
        val event = synchronized(lock) {
            require(waiter == null) { "Only one notification action listener can wait at a time" }
            if (events.isEmpty()) {
                waiter = completion
                null
            } else {
                events.removeFirst().also { persistLocked() }
            }
        }
        if (event != null) completion.complete(ModuleResultStatus.SUCCESS, event)
    }

    internal fun close(message: String) {
        val pending = synchronized(lock) { waiter.also { waiter = null } }
        pending?.complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    internal fun prepareReload() {
        synchronized(lock) { waiter = null }
    }

    private fun report(type: Int, key: String, text: String, dataJson: String, deepLink: String, status: Int) {
        val payload = WireMap.encode(
            mapOf(
                "type" to WireValue.Integer(type.toLong()),
                "conversation" to WireValue.Text(key),
                "text" to WireValue.Text(text),
                "data" to WireValue.Text(dataJson),
                "deepLink" to WireValue.Text(deepLink),
                "handledNatively" to WireValue.Flag(status >= 0),
                "statusCode" to WireValue.Integer(status.coerceAtLeast(0).toLong()),
                "timestamp" to WireValue.Integer(System.currentTimeMillis()),
            ),
        )
        val pending = synchronized(lock) {
            val value = waiter
            if (value == null) {
                if (events.size >= MAX_QUEUED_EVENTS) events.removeFirst()
                events.addLast(payload)
                persistLocked()
            } else {
                waiter = null
            }
            value
        }
        pending?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    private fun persistLocked() {
        val storage = preferences ?: return
        val encoded = JSONArray()
        events.forEach { encoded.put(Base64.encodeToString(it, Base64.NO_WRAP)) }
        storage.edit().putString(PREFERENCES_EVENTS, encoded.toString()).apply()
    }

    private const val MAX_QUEUED_EVENTS = 64
    private const val PREFERENCES_EVENTS = "events"
    private const val PREFERENCES_NAME = "pam-native-notification-actions"
}

/** `{name}` placeholders resolved from push data and action context. */
internal object NotificationTemplate {
    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_.:-]{1,128})\\}")

    fun variables(dataJson: String): Map<String, String> {
        val data = runCatching { JSONObject(dataJson) }.getOrDefault(JSONObject())
        return buildMap {
            data.keys().forEach { name ->
                when (val value = data.opt(name)) {
                    is String -> put(name, value)
                    is Number, is Boolean -> put(name, value.toString())
                }
            }
        }
    }

    fun render(
        template: String,
        variables: Map<String, String>,
        context: Context? = null,
        encode: (String) -> String = { it },
    ): String = PLACEHOLDER.replace(template) { match ->
        encode(resolve(match.groupValues[1], variables, context))
    }

    /** True when every placeholder in the template has a value. */
    fun complete(template: String, variables: Map<String, String>): Boolean =
        PLACEHOLDER.findAll(template).all { match ->
            val name = match.groupValues[1]
            name in variables || name == "uuid" || name == "now" || name.startsWith("storage:")
        }

    private fun resolve(name: String, variables: Map<String, String>, context: Context?): String = when {
        name in variables -> variables.getValue(name)
        name == "uuid" -> UUID.randomUUID().toString()
        name == "now" -> System.currentTimeMillis().toString()
        name.startsWith("storage:") && context != null -> {
            val key = name.removePrefix("storage:")
            require(STORAGE_KEY.matches(key)) { "Invalid storage placeholder" }
            context.getSharedPreferences("pam-native", Context.MODE_PRIVATE).getString(key, null).orEmpty()
        }
        else -> ""
    }

    private val STORAGE_KEY = Regex("^[A-Za-z0-9_.-]{1,128}$")
}

/** Executes the declarative HTTP request attached to a notification action. */
internal object NotificationEndpoint {
    fun send(context: Context, endpoint: JSONObject, variables: Map<String, String>): Int {
        val method = endpoint.optString("method", "POST").uppercase()
        require(method in setOf("POST", "PUT", "PATCH", "DELETE")) { "Unsupported endpoint method" }
        val url = NotificationTemplate.render(endpoint.getString("url"), variables, context) {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val uri = URI(url)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
            "Notification endpoints require HTTPS"
        }
        require(uri.host != null && uri.userInfo == null) { "Invalid notification endpoint" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val headers = endpoint.optJSONObject("headers") ?: JSONObject()
            headers.keys().forEach { name ->
                require(HEADER.matches(name)) { "Invalid endpoint header" }
                val value = NotificationTemplate.render(headers.getString(name), variables, context)
                require('\r' !in value && '\n' !in value && value.length <= 8_192) { "Invalid endpoint header value" }
                connection.setRequestProperty(name, value)
            }
            val body = endpoint.opt("body")
            if (body is JSONObject || body is JSONArray) {
                val rendered = renderJson(body, variables, context).toString().toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(rendered.size)
                connection.outputStream.use { it.write(rendered) }
            }
            val status = connection.responseCode
            runCatching { (if (status >= 400) connection.errorStream else connection.inputStream)?.close() }
            return status
        } finally {
            connection.disconnect()
        }
    }

    private fun renderJson(value: Any?, variables: Map<String, String>, context: Context): Any? = when (value) {
        is JSONObject -> JSONObject().apply {
            value.keys().forEach { put(it, renderJson(value.get(it), variables, context)) }
        }
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(renderJson(value.get(index), variables, context))
        }
        is String -> NotificationTemplate.render(value, variables, context)
        else -> value
    }

    private val HEADER = Regex("^[A-Za-z0-9-]{1,64}$")
}
