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
        val endpoint = if (type == NotificationActionType.REPLY) spec?.replyEndpoint else spec?.markReadEndpoint
        val variables = NotificationTemplate.variables(spec?.dataJson ?: "{}") + mapOf(
            "reply" to text,
            "conversation" to key,
        )
        // An endpoint authenticated per account ({credential:user_id}) is never
        // sent when the push's account has no token here: the notification is
        // dismissed and nothing goes out with another account's session.
        val missingCredential = endpoint != null && NotificationEndpoint.missingCredential(endpoint, variables) {
            PamNotificationCredentials.token(context, it)
        }
        if (type == NotificationActionType.REPLY && !missingCredential) {
            PamConversationNotifications.appendOwnReply(context, key, text)
        } else {
            PamConversationNotifications.cancel(context, key)
        }
        val status = when {
            endpoint == null || missingCredential -> -1
            else -> runCatching { NotificationEndpoint.send(context, endpoint, variables) }.getOrDefault(0)
        }
        report(type, key, text, spec?.dataJson ?: "{}", spec?.deepLink.orEmpty(), status, missingCredential)
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

    private fun report(
        type: Int,
        key: String,
        text: String,
        dataJson: String,
        deepLink: String,
        status: Int,
        missingCredential: Boolean,
    ) {
        val payload = WireMap.encode(
            mapOf(
                "type" to WireValue.Integer(type.toLong()),
                "conversation" to WireValue.Text(key),
                "text" to WireValue.Text(text),
                "data" to WireValue.Text(dataJson),
                "deepLink" to WireValue.Text(deepLink),
                "handledNatively" to WireValue.Flag(status >= 0),
                "statusCode" to WireValue.Integer(status.coerceAtLeast(0).toLong()),
                "credentialMissing" to WireValue.Flag(missingCredential),
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

/**
 * `{name}` placeholders resolved from push data and action context.
 * `{credential:user_id|recipient_user_id}` is the token stored with
 * PamNotificationCredentials for the account named by the first non-empty
 * listed push data field.
 */
internal object NotificationTemplate {
    private val PLACEHOLDER = Regex("\\{([A-Za-z0-9_.:|-]{1,128})\\}")
    private const val CREDENTIAL = "credential:"

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
    ): String = render(template, variables, context, credentialsOf(context), encode)

    fun render(
        template: String,
        variables: Map<String, String>,
        context: Context?,
        credentials: (String) -> String?,
        encode: (String) -> String = { it },
    ): String = PLACEHOLDER.replace(template) { match ->
        encode(resolve(match.groupValues[1], variables, context, credentials))
    }

    /** Account id a `credential:` placeholder points at, or "" when the push names none. */
    fun credentialAccount(name: String, variables: Map<String, String>): String {
        if (!name.startsWith(CREDENTIAL)) return ""
        return name.removePrefix(CREDENTIAL)
            .split('|')
            .asSequence()
            .map { variables[it].orEmpty().trim() }
            .firstOrNull(String::isNotEmpty)
            .orEmpty()
    }

    /** True when a `credential:` placeholder in the template has no stored token. */
    fun missingCredential(template: String, variables: Map<String, String>, credentials: (String) -> String?): Boolean =
        PLACEHOLDER.findAll(template).any { match ->
            val name = match.groupValues[1]
            name.startsWith(CREDENTIAL) && credentialAccount(name, variables).let { account ->
                account.isEmpty() || credentials(account).isNullOrEmpty()
            }
        }

    private fun credentialsOf(context: Context?): (String) -> String? =
        { account -> context?.let { PamNotificationCredentials.token(it, account) } }

    /** True when every placeholder in the template has a value. */
    fun complete(template: String, variables: Map<String, String>): Boolean =
        PLACEHOLDER.findAll(template).all { match ->
            val name = match.groupValues[1]
            name in variables || name == "uuid" || name == "now" ||
                name.startsWith("storage:") || name.startsWith(CREDENTIAL)
        }

    private fun resolve(
        name: String,
        variables: Map<String, String>,
        context: Context?,
        credentials: (String) -> String?,
    ): String = when {
        name.startsWith(CREDENTIAL) -> credentialAccount(name, variables)
            .takeIf(String::isNotEmpty)
            ?.let(credentials)
            .orEmpty()
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
    /** Credentials are only ever resolved in headers, never in a URL or a body. */
    private val NO_CREDENTIALS: (String) -> String? = { null }

    /** True when a header needs a credential the push's account has no token for. */
    fun missingCredential(endpoint: JSONObject, variables: Map<String, String>, credentials: (String) -> String?): Boolean {
        val headers = endpoint.optJSONObject("headers") ?: return false
        return headers.keys().asSequence().any { name ->
            NotificationTemplate.missingCredential(headers.optString(name), variables, credentials)
        }
    }

    fun send(context: Context, endpoint: JSONObject, variables: Map<String, String>): Int {
        val method = endpoint.optString("method", "POST").uppercase()
        require(method in setOf("POST", "PUT", "PATCH", "DELETE")) { "Unsupported endpoint method" }
        val url = NotificationTemplate.render(endpoint.getString("url"), variables, context, NO_CREDENTIALS) {
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
        is String -> NotificationTemplate.render(value, variables, context, NO_CREDENTIALS)
        else -> value
    }

    private val HEADER = Regex("^[A-Za-z0-9-]{1,64}$")
}
