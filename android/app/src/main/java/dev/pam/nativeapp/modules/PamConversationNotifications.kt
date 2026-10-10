package dev.pam.nativeapp.modules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Sender of one conversation message. */
internal data class ConversationPerson(
    val name: String,
    val avatar: String = "",
    val key: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("avatar", avatar).put("key", key)

    companion object {
        fun from(json: JSONObject?): ConversationPerson? = json?.let {
            val name = it.optString("name").trim()
            if (name.isEmpty()) null else ConversationPerson(
                name.take(256),
                it.optString("avatar").take(8_192),
                it.optString("key").take(256),
            )
        }
    }
}

internal data class ConversationMessage(
    val id: String,
    val text: String,
    val timestamp: Long,
    /** Null for messages written by the device user. */
    val sender: ConversationPerson?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("timestamp", timestamp)
        .apply { sender?.let { put("sender", it.toJson()) } }

    companion object {
        fun from(json: JSONObject): ConversationMessage = ConversationMessage(
            id = json.optString("id").take(256),
            text = json.optString("text").take(MAX_TEXT),
            timestamp = json.optLong("timestamp").takeIf { it > 0 } ?: System.currentTimeMillis(),
            sender = ConversationPerson.from(json.optJSONObject("sender")),
        )

        const val MAX_TEXT = 4_096
    }
}

/**
 * Declarative MessagingStyle conversation. The same spec is produced by
 * Notifications::conversation() in PHP and by PushRendering rules while PHP is
 * suspended, so both paths render identical notifications.
 */
internal data class ConversationSpec(
    val key: String,
    val title: String = "",
    val group: Boolean = false,
    val self: ConversationPerson = ConversationPerson("You"),
    val messages: List<ConversationMessage> = emptyList(),
    val replyLabel: String = "",
    val markReadLabel: String = "",
    val replyEndpoint: JSONObject? = null,
    val markReadEndpoint: JSONObject? = null,
    val deepLink: String = "",
    val dataJson: String = "{}",
    val importance: Int = 3,
    val channelId: String = DEFAULT_CHANNEL,
    val channelName: String = "Messages",
    val silent: Boolean = false,
    /** ReplyFailures config: send first, keep failed replies visible. */
    val replyFailures: JSONObject? = null,
    /** The reply that failed: failedText, text, retry, keepReply, actionId, uuid, timestamp. */
    val failure: JSONObject? = null,
) {
    init {
        require(KEY.matches(key)) { "Conversation key must contain 1-128 safe characters" }
        require(CHANNEL.matches(channelId)) { "Invalid notification channel id" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("group", group)
        .put("self", self.toJson())
        .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        .put("replyLabel", replyLabel)
        .put("markReadLabel", markReadLabel)
        .put("replyEndpoint", replyEndpoint ?: JSONObject.NULL)
        .put("markReadEndpoint", markReadEndpoint ?: JSONObject.NULL)
        .put("deepLink", deepLink)
        .put("data", dataJson)
        .put("importance", importance)
        .put("channelId", channelId)
        .put("channelName", channelName)
        .put("silent", silent)
        .put("replyFailures", replyFailures ?: JSONObject.NULL)
        .put("failure", failure ?: JSONObject.NULL)

    companion object {
        const val DEFAULT_CHANNEL = "pam-messages"
        private val KEY = Regex("^[A-Za-z0-9_.:@#/-]{1,128}$")
        private val CHANNEL = Regex("^[A-Za-z0-9_.-]{1,64}$")

        fun from(json: JSONObject): ConversationSpec {
            val messages = json.optJSONArray("messages") ?: JSONArray()
            return ConversationSpec(
                key = json.getString("key"),
                title = json.optString("title").take(512),
                group = json.optBoolean("group"),
                self = ConversationPerson.from(json.optJSONObject("self")) ?: ConversationPerson("You"),
                messages = (0 until minOf(messages.length(), MAX_HISTORY)).map {
                    ConversationMessage.from(messages.getJSONObject(it))
                }.filter { it.text.isNotBlank() },
                replyLabel = json.optString("replyLabel").take(64),
                markReadLabel = json.optString("markReadLabel").take(64),
                replyEndpoint = json.optJSONObject("replyEndpoint"),
                markReadEndpoint = json.optJSONObject("markReadEndpoint"),
                deepLink = json.optString("deepLink").take(8_192),
                dataJson = json.optString("data", "{}").take(262_144).ifBlank { "{}" },
                importance = json.optInt("importance", 3).coerceIn(1, 4),
                channelId = json.optString("channelId").ifBlank { DEFAULT_CHANNEL },
                channelName = json.optString("channelName").ifBlank { "Messages" }.take(128),
                silent = json.optBoolean("silent"),
                replyFailures = json.optJSONObject("replyFailures"),
                failure = json.optJSONObject("failure"),
            )
        }

        const val MAX_HISTORY = 25
    }
}

public object PamConversationNotifications {
    internal const val NOTIFICATION_ID = 0x50414d
    internal const val ACTION_REPLY = "dev.pam.nativeapp.action.CONVERSATION_REPLY"
    internal const val ACTION_MARK_READ = "dev.pam.nativeapp.action.CONVERSATION_MARK_READ"
    internal const val EXTRA_KEY = "dev.pam.nativeapp.conversation.KEY"
    internal const val REMOTE_INPUT_KEY = "pam.reply"
    internal const val EXTRA_RETRY_TEXT = "dev.pam.nativeapp.conversation.RETRY_TEXT"
    internal const val EXTRA_RETRY_ACTION_ID = "dev.pam.nativeapp.conversation.RETRY_ACTION_ID"
    internal const val EXTRA_RETRY_UUID = "dev.pam.nativeapp.conversation.RETRY_UUID"
    internal const val ACTION_BUTTON = "dev.pam.nativeapp.action.NOTIFICATION_BUTTON"
    internal const val EXTRA_BUTTON_ID = "dev.pam.nativeapp.button.ID"
    internal const val EXTRA_BUTTON_ENDPOINT = "dev.pam.nativeapp.button.ENDPOINT"
    internal const val EXTRA_BUTTON_DATA = "dev.pam.nativeapp.button.DATA"
    internal const val EXTRA_BUTTON_DEEP_LINK = "dev.pam.nativeapp.button.DEEP_LINK"
    internal const val EXTRA_BUTTON_TAG = "dev.pam.nativeapp.button.TAG"
    internal const val EXTRA_BUTTON_NOTIFICATION = "dev.pam.nativeapp.button.NOTIFICATION"
    internal const val EXTRA_BUTTON_DISMISS = "dev.pam.nativeapp.button.DISMISS"
    private const val PREFERENCES = "pam-native-conversations"
    private const val AVATAR_BYTES = 2 * 1024 * 1024
    private val lock = Any()

    /** Merges messages into the stored history and posts the notification. */
    internal fun show(context: Context, incoming: ConversationSpec): ConversationSpec {
        val merged = synchronized(lock) {
            val previous = load(context, incoming.key)
            val history = LinkedHashMap<String, ConversationMessage>()
            (previous?.messages.orEmpty() + incoming.messages).forEachIndexed { index, message ->
                history[message.id.ifEmpty { "auto-${message.timestamp}-$index-${message.text.hashCode()}" }] = message
            }
            val messages = history.values.sortedBy { it.timestamp }.takeLast(ConversationSpec.MAX_HISTORY)
            incoming.copy(messages = messages).also { store(context, it) }
        }
        post(context, merged)
        return merged
    }

    /** Appends the device user's reply so Android stops the inline-reply spinner. */
    internal fun appendOwnReply(context: Context, key: String, text: String): ConversationSpec? {
        val spec = synchronized(lock) { load(context, key) } ?: return null
        return show(
            context,
            spec.copy(
                messages = listOf(ConversationMessage("reply-${System.nanoTime()}", text, System.currentTimeMillis(), null)),
                silent = true,
                failure = null,
            ),
        )
    }

    /**
     * Keeps a reply the endpoint did not deliver in the notification: the
     * unsent text, the reason, the retry button and the draft for the app.
     */
    internal fun showFailure(context: Context, key: String, failure: JSONObject): ConversationSpec? {
        val spec = synchronized(lock) { load(context, key) } ?: return null
        return show(context, spec.copy(messages = emptyList(), silent = true, failure = failure))
    }

    /** Intent that opens the app from the notification (data + the failed reply as the draft field). */
    internal fun openIntent(context: Context, spec: ConversationSpec): Intent? {
        var data = spec.dataJson
        val failure = spec.failure
        val draftField = spec.replyFailures?.optString("draftField").orEmpty()
        if (failure != null && draftField.isNotEmpty()) {
            data = runCatching { JSONObject(spec.dataJson).put(draftField, failure.optString("failedText")).toString() }
                .getOrDefault(spec.dataJson)
        }
        return context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("pam.notification.opened", true)
            putExtra("pam.notification.id", spec.key)
            putExtra("pam.notification.title", spec.title.ifBlank { spec.messages.lastOrNull()?.sender?.name.orEmpty() })
            putExtra("pam.notification.body", spec.messages.lastOrNull()?.text.orEmpty())
            putExtra("pam.notification.data", data)
            putExtra("pam.notification.deepLink", spec.deepLink)
        }
    }

    internal fun cancel(context: Context, key: String) {
        synchronized(lock) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(key).apply()
        }
        NotificationManagerCompat.from(context).cancel(key, NOTIFICATION_ID)
    }

    internal fun load(context: Context, key: String): ConversationSpec? = runCatching {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.let { ConversationSpec.from(JSONObject(it)) }
    }.getOrNull()

    private fun store(context: Context, spec: ConversationSpec) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit().putString(spec.key, spec.toJson().toString())
        val keys = preferences.all.keys - spec.key
        if (keys.size >= MAX_CONVERSATIONS) keys.take(keys.size - MAX_CONVERSATIONS + 1).forEach(editor::remove)
        editor.apply()
    }

    internal fun build(context: Context, spec: ConversationSpec): NotificationCompat.Builder {
        ensureChannel(context, spec)
        val selfPerson = person(context, spec.self, "self")
        val style = NotificationCompat.MessagingStyle(selfPerson)
            .setGroupConversation(spec.group)
        if (spec.group || spec.title.isNotBlank()) style.conversationTitle = spec.title.ifBlank { null }
        val people = HashMap<String, Person>()
        spec.messages.forEach { message ->
            val sender = message.sender?.let { sender ->
                people.getOrPut(sender.key.ifEmpty { sender.name }) { person(context, sender, sender.key.ifEmpty { sender.name }) }
            }
            style.addMessage(NotificationCompat.MessagingStyle.Message(message.text, message.timestamp, sender))
        }
        val failure = spec.failure
        val failureSender = spec.replyFailures?.optString("sender").orEmpty().ifBlank { "Not sent" }
        if (failure != null) {
            // The unsent reply as the user's own line, then the reason.
            val at = failure.optLong("timestamp").takeIf { it > 0 } ?: System.currentTimeMillis()
            style.addMessage(NotificationCompat.MessagingStyle.Message(failure.optString("failedText"), at, null as Person?))
            val notice = Person.Builder().setName(failureSender).setKey("pam-reply-failure").build()
            style.addMessage(NotificationCompat.MessagingStyle.Message(failure.optString("text"), at, notice))
        }
        val launch = openIntent(context, spec)
        val builder = NotificationCompat.Builder(context, spec.channelId)
            .setSmallIcon(dev.pam.nativeapp.R.drawable.pam_icon)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShortcutId(spec.key)
            .setAutoCancel(true)
            .setOnlyAlertOnce(spec.silent)
            .setPriority(priority(spec.importance))
            .setGroup("pam-conversation-${spec.key}")
            .setWhen(spec.messages.lastOrNull()?.timestamp ?: System.currentTimeMillis())
            .setShowWhen(true)
        spec.messages.lastOrNull()?.sender?.let { builder.addPerson(people[it.key.ifEmpty { it.name }]) }
        if (failure != null) {
            builder.setSubText(failureSender).setContentText(failure.optString("text"))
            if (failure.optBoolean("retry")) {
                val retryIntent = actionIntent(context, ACTION_REPLY, spec.key)
                    .putExtra(EXTRA_RETRY_TEXT, failure.optString("failedText"))
                    .putExtra(EXTRA_RETRY_ACTION_ID, failure.optString("actionId"))
                    .putExtra(EXTRA_RETRY_UUID, failure.optString("uuid"))
                val pending = PendingIntent.getBroadcast(
                    context,
                    requestCode(spec.key, 3),
                    retryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, spec.replyFailures?.optString("retryLabel").orEmpty().ifBlank { "Try again" }, pending)
                        .setShowsUserInterface(false)
                        .build(),
                )
            }
        }
        launch?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    requestCode(spec.key, 0),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        if (spec.replyLabel.isNotBlank() && failure?.optBoolean("keepReply", true) != false) {
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(spec.replyLabel).build()
            val replyIntent = actionIntent(context, ACTION_REPLY, spec.key)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(spec.key, 1),
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, spec.replyLabel, pending)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        if (spec.markReadLabel.isNotBlank()) {
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(spec.key, 2),
                actionIntent(context, ACTION_MARK_READ, spec.key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, spec.markReadLabel, pending)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        return builder
    }

    private fun post(context: Context, spec: ConversationSpec) {
        val manager = NotificationManagerCompat.from(context)
        if (!canPostNotifications(context, manager)) return
        try {
            manager.notify(spec.key, NOTIFICATION_ID, build(context, spec).build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    internal fun canPostNotifications(context: Context, manager: NotificationManagerCompat): Boolean =
        manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            )

    internal fun actionIntent(context: Context, action: String, key: String): Intent =
        Intent(context, PamNotificationActionReceiver::class.java)
            .setAction(action)
            .setPackage(context.packageName)
            .putExtra(EXTRA_KEY, key)

    internal fun requestCode(key: String, action: Int): Int = (key.hashCode() * 31) + action

    private fun priority(importance: Int): Int = when (importance) {
        1 -> NotificationCompat.PRIORITY_LOW
        3 -> NotificationCompat.PRIORITY_HIGH
        4 -> NotificationCompat.PRIORITY_MAX
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    private fun ensureChannel(context: Context, spec: ConversationSpec) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(spec.channelId) != null) return
        val importance = when (spec.importance) {
            1 -> NotificationManager.IMPORTANCE_LOW
            3 -> NotificationManager.IMPORTANCE_HIGH
            4 -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        manager.createNotificationChannel(NotificationChannel(spec.channelId, spec.channelName, importance))
    }

    private fun person(context: Context, value: ConversationPerson, key: String): Person =
        Person.Builder()
            .setName(value.name)
            .setKey(key)
            .apply { avatar(context, value.avatar)?.let { setIcon(IconCompat.createWithBitmap(it)) } }
            .build()

    /** Loads an avatar from the image disk cache, the private sandbox or HTTPS. */
    internal fun avatar(context: Context, source: String): Bitmap? {
        if (source.isBlank()) return null
        return runCatching {
            val bytes = when {
                source.startsWith("https://") || source.startsWith("http://") -> cachedOrDownload(context, source)
                source.startsWith("pam-file:///") -> resolvePrivateFile(
                    File(context.filesDir, "pam-files"),
                    java.net.URLDecoder.decode(source.removePrefix("pam-file:///"), "UTF-8"),
                ).takeIf { it.length() <= AVATAR_BYTES }?.readBytes()
                else -> resolvePrivateFile(File(context.filesDir, "pam-files"), source)
                    .takeIf { it.length() <= AVATAR_BYTES }?.readBytes()
            } ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            var sample = 1
            while (options.outWidth / (sample * 2) >= AVATAR_EDGE && options.outHeight / (sample * 2) >= AVATAR_EDGE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let(::circle)
        }.getOrNull()
    }

    private fun cachedOrDownload(context: Context, source: String): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val cached = File(File(context.cacheDir, "pam-images-v1"), "$digest.image")
        if (cached.isFile && cached.length() in 1..AVATAR_BYTES.toLong()) return cached.readBytes()
        val uri = URI(source)
        require(uri.scheme == "https" || dev.pam.nativeapp.BuildConfig.DEBUG) { "Avatars require HTTPS" }
        val connection = URL(source).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= AVATAR_BYTES) { "Avatar is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun circle(source: Bitmap): Bitmap {
        val edge = minOf(source.width, source.height, AVATAR_EDGE * 2)
        val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawOval(0f, 0f, edge.toFloat(), edge.toFloat(), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, edge, edge), paint)
        return output
    }

    private const val AVATAR_EDGE = 128
    private const val MAX_CONVERSATIONS = 64
}

/** Receives inline replies and mark-as-read taps, even while PHP is suspended. */
public class PamNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PamConversationNotifications.ACTION_BUTTON) {
            val id = intent.getStringExtra(PamConversationNotifications.EXTRA_BUTTON_ID) ?: return
            val endpoint = runCatching { JSONObject(intent.getStringExtra(PamConversationNotifications.EXTRA_BUTTON_ENDPOINT).orEmpty()) }.getOrNull() ?: return
            val pending = goAsync()
            val appContext = context.applicationContext
            thread(name = "pam-notification-button") {
                try {
                    PamNotificationActions.handleButton(
                        appContext,
                        id,
                        endpoint,
                        intent.getStringExtra(PamConversationNotifications.EXTRA_BUTTON_DATA) ?: "{}",
                        intent.getStringExtra(PamConversationNotifications.EXTRA_BUTTON_DEEP_LINK).orEmpty(),
                        intent.getStringExtra(PamConversationNotifications.EXTRA_BUTTON_TAG).orEmpty(),
                        intent.getIntExtra(PamConversationNotifications.EXTRA_BUTTON_NOTIFICATION, 0),
                        intent.getBooleanExtra(PamConversationNotifications.EXTRA_BUTTON_DISMISS, true),
                    )
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val key = intent.getStringExtra(PamConversationNotifications.EXTRA_KEY) ?: return
        val type = when (intent.action) {
            PamConversationNotifications.ACTION_REPLY -> NotificationActionType.REPLY
            PamConversationNotifications.ACTION_MARK_READ -> NotificationActionType.MARK_READ
            else -> return
        }
        val text = if (type == NotificationActionType.REPLY) {
            (RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(PamConversationNotifications.REMOTE_INPUT_KEY)
                ?.toString()
                ?.trim()
                ?.ifEmpty { null }
                ?: intent.getStringExtra(PamConversationNotifications.EXTRA_RETRY_TEXT)?.trim().orEmpty()
            ).take(ConversationMessage.MAX_TEXT)
        } else {
            ""
        }
        if (type == NotificationActionType.REPLY && text.isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "pam-notification-action") {
            try {
                val retry = intent.getStringExtra(PamConversationNotifications.EXTRA_RETRY_ACTION_ID)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { ReplyRetry(it, intent.getStringExtra(PamConversationNotifications.EXTRA_RETRY_UUID).orEmpty()) }
                PamNotificationActions.handle(appContext, type, key, text, retry)
            } finally {
                pending.finish()
            }
        }
    }
}
