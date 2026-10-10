package dev.pam.nativeapp.modules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Route currently focused by the PHP navigator, reported by the runtime.
 * Push rendering uses it to suppress notifications for the open screen.
 */
public object PamActiveRoute {
    @Volatile internal var name: String = ""
    @Volatile internal var params: JSONObject = JSONObject()
    @Volatile internal var path: String = ""
    @Volatile internal var foreground: Boolean = false

    internal fun update(name: String, paramsJson: String, path: String) {
        this.name = name.take(256)
        this.params = runCatching { JSONObject(paramsJson) }.getOrDefault(JSONObject())
        this.path = path.take(8_192)
    }

    @JvmStatic
    public fun setForeground(foreground: Boolean) {
        this.foreground = foreground
    }

    /** Suppression entries are `{route, params}` or `{path}` templates. */
    internal fun matches(rules: JSONArray?, variables: Map<String, String>): Boolean {
        if (!foreground || rules == null) return false
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: continue
            val routePath = rule.optString("path")
            if (routePath.isNotEmpty()) {
                if (!NotificationTemplate.complete(routePath, variables)) continue
                val expected = normalize(NotificationTemplate.render(routePath, variables))
                if (path.isNotEmpty() && normalize(path) == expected) return true
                continue
            }
            if (rule.optString("route") != name || name.isEmpty()) continue
            val expectedParams = rule.optJSONObject("params") ?: JSONObject()
            val matched = expectedParams.keys().asSequence().all { key ->
                val template = expectedParams.optString(key)
                NotificationTemplate.complete(template, variables) &&
                    params.opt(key)?.toString() == NotificationTemplate.render(template, variables)
            }
            if (matched) return true
        }
        return false
    }

    private fun normalize(value: String): String =
        "/" + value.substringBefore('?').substringBefore('#').trim('/')
}

/**
 * Declarative push rendering. PHP registers rules once; the Firebase service
 * renders matching data-only pushes natively, even when PHP is suspended or
 * the process was started just for the push.
 */
public object PamPushRendering {
    private const val PREFERENCES = "pam-native-push-rendering"
    internal const val KIND_CONVERSATION = 1
    internal const val KIND_NOTIFICATION = 2
    internal const val KIND_DISMISS = 3

    internal fun register(context: Context, ruleJson: String) {
        val rule = JSONObject(ruleJson)
        val type = rule.getString("type")
        require(type.isNotBlank() && type.length <= 128) { "Push rendering type is invalid" }
        require(rule.optInt("kind") in KIND_CONVERSATION..KIND_DISMISS) { "Push rendering kind is invalid" }
        preferences(context).edit().putString(key(rule.optString("field", "type"), type, rule.optJSONObject("where"), rule.optJSONArray("requires")), rule.toString()).apply()
    }

    internal fun forget(context: Context, field: String, type: String) {
        val prefix = key(field, type, null, null)
        val editor = preferences(context).edit()
        preferences(context).all.keys.filter { it == prefix || it.startsWith(prefix + "\u0000") }.forEach(editor::remove)
        editor.apply()
    }

    /** The registered rule for this push: type field plus every `where` condition; the most specific wins. */
    internal fun select(context: Context, data: JSONObject): JSONObject? =
        preferences(context).all.values.asSequence()
            .mapNotNull { value -> (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } }
            .filter { candidate ->
                data.opt(candidate.optString("field", "type"))?.toString() == candidate.optString("type") &&
                    (candidate.optJSONObject("where") ?: JSONObject()).let { where ->
                        where.keys().asSequence().all { field -> data.opt(field)?.toString() == where.optString(field) }
                    } &&
                    (candidate.optJSONArray("requires") ?: JSONArray()).let { required ->
                        (0 until required.length()).all { index ->
                            !data.opt(required.optString(index))?.toString().isNullOrBlank() && data.opt(required.optString(index)) != JSONObject.NULL
                        }
                    }
            }
            .maxByOrNull { (it.optJSONObject("where")?.length() ?: 0) + (it.optJSONArray("requires")?.length() ?: 0) }

    internal fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    /** Returns true when a rule rendered (or intentionally dismissed) the push. */
    @JvmStatic
    public fun render(context: Context, id: String, title: String, body: String, dataJson: String): Boolean {
        val data = runCatching { JSONObject(dataJson) }.getOrNull() ?: return false
        val rule = select(context, data) ?: return false
        val variables = NotificationTemplate.variables(dataJson) + mapOf(
            "_id" to id,
            "_title" to title,
            "_body" to body,
        )
        return runCatching { apply(context, rule, variables, dataJson) }.getOrDefault(false)
    }

    private fun apply(context: Context, rule: JSONObject, variables: Map<String, String>, dataJson: String): Boolean {
        fun text(template: String): String =
            if (template.isEmpty()) "" else NotificationTemplate.render(template, variables).trim()

        when (rule.getInt("kind")) {
            KIND_DISMISS -> {
                val key = text(rule.optJSONObject("conversation")?.optString("key").orEmpty())
                if (key.isEmpty()) return false
                PamConversationNotifications.cancel(context, key)
                return true
            }
            KIND_CONVERSATION -> {
                if (PamActiveRoute.matches(rule.optJSONArray("suppress"), variables)) return true
                val conversation = rule.getJSONObject("conversation")
                val message = rule.getJSONObject("message")
                val key = text(conversation.optString("key"))
                val messageText = text(message.optString("text"))
                val sender = text(message.optString("sender"))
                if (key.isEmpty() || messageText.isEmpty() || sender.isEmpty()) return false
                val reply = rule.optJSONObject("reply")
                // hideWhen: the push says this recipient may not reply (e.g. can_reply = "0").
                val replyHidden = reply?.optJSONObject("hideWhen")?.let { hide ->
                    hide.keys().asSequence().any { field -> variables[field] == hide.optString(field) }
                } ?: false
                val markRead = rule.optJSONObject("markRead")
                PamConversationNotifications.show(
                    context,
                    ConversationSpec(
                        key = key,
                        title = text(conversation.optString("title")),
                        group = text(conversation.optString("group")).lowercase() in setOf("1", "true", "yes"),
                        self = ConversationPerson(rule.optString("self").ifBlank { "You" }),
                        messages = listOf(
                            ConversationMessage(
                                id = text(message.optString("id")),
                                text = messageText.take(ConversationMessage.MAX_TEXT),
                                timestamp = timestamp(text(message.optString("timestamp"))),
                                sender = ConversationPerson(
                                    name = sender,
                                    avatar = text(message.optString("avatar")),
                                    key = text(message.optString("senderKey")),
                                ),
                            ),
                        ),
                        replyLabel = if (replyHidden) "" else reply?.optString("label").orEmpty(),
                        replyEndpoint = reply?.optJSONObject("endpoint"),
                        markReadLabel = markRead?.optString("label").orEmpty(),
                        markReadEndpoint = markRead?.optJSONObject("endpoint"),
                        deepLink = text(rule.optString("deepLink")),
                        dataJson = dataJson,
                        importance = rule.optInt("importance", 3),
                        channelId = rule.optString("channelId").ifBlank { ConversationSpec.DEFAULT_CHANNEL },
                        channelName = rule.optString("channelName").ifBlank { "Messages" },
                        replyFailures = rule.optJSONObject("replyFailures"),
                        failure = null,
                    ),
                )
                return true
            }
            KIND_NOTIFICATION -> {
                if (PamActiveRoute.matches(rule.optJSONArray("suppress"), variables)) return true
                val title = text(rule.optString("title"))
                if (title.isEmpty()) return false
                notify(context, rule, variables["_id"].orEmpty(), title, text(rule.optString("body")), text(rule.optString("deepLink")), dataJson)
                return true
            }
        }
        return false
    }

    private fun notify(
        context: Context,
        rule: JSONObject,
        id: String,
        title: String,
        body: String,
        deepLink: String,
        dataJson: String,
    ) {
        val notification = buildNotification(context, rule, id, title, body, deepLink, dataJson)
        val compat = NotificationManagerCompat.from(context)
        if (!PamConversationNotifications.canPostNotifications(context, compat)) return
        try {
            compat.notify(TAG, id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    /** Standard notification of a rule, with its background buttons (PushRenderingRule::action()). */
    internal fun buildNotification(
        context: Context,
        rule: JSONObject,
        id: String,
        title: String,
        body: String,
        deepLink: String,
        dataJson: String,
    ): android.app.Notification {
        val channelId = rule.optString("channelId").ifBlank { "pam-push" }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    rule.optString("channelName").ifBlank { "Notifications" },
                    if (rule.optInt("importance", 2) >= 3) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("pam.notification.opened", true)
            putExtra("pam.notification.id", id)
            putExtra("pam.notification.title", title)
            putExtra("pam.notification.body", body)
            putExtra("pam.notification.data", dataJson)
            putExtra("pam.notification.deepLink", deepLink)
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(dev.pam.nativeapp.R.drawable.pam_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply {
                launch?.let {
                    setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            id.hashCode(),
                            it,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
            }
        val buttons = rule.optJSONArray("actions") ?: JSONArray()
        for (index in 0 until minOf(buttons.length(), 3)) {
            val button = buttons.optJSONObject(index) ?: continue
            val endpoint = button.optJSONObject("endpoint") ?: continue
            val intent = Intent(context, PamNotificationActionReceiver::class.java)
                .setAction(PamConversationNotifications.ACTION_BUTTON)
                .setPackage(context.packageName)
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_ID, button.optString("id"))
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_ENDPOINT, endpoint.toString())
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_DATA, dataJson)
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_DEEP_LINK, deepLink)
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_TAG, TAG)
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_NOTIFICATION, id.hashCode())
                .putExtra(PamConversationNotifications.EXTRA_BUTTON_DISMISS, button.optBoolean("dismiss", true))
            val pending = PendingIntent.getBroadcast(
                context,
                (id.hashCode() * 31) + 16 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, button.optString("label"), pending)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        return builder.build()
    }

    internal fun timestamp(value: String): Long {
        if (value.isEmpty()) return System.currentTimeMillis()
        value.toLongOrNull()?.let { return if (it in 1..99_999_999_999L) it * 1_000 else it }
        value.toDoubleOrNull()?.let { return (if (it < 100_000_000_000.0) it * 1_000 else it).toLong() }
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    }

    private const val TAG = "pam-push"

    private fun key(field: String, type: String, where: JSONObject?, requires: JSONArray?): String {
        val base = "$field\u0000$type"
        val conditions = (where?.keys()?.asSequence()?.sorted()?.map { "$it=${where.optString(it)}" }?.toList() ?: emptyList()) +
            (0 until (requires?.length() ?: 0)).map { "$" + requires!!.optString(it) }.sorted()
        return if (conditions.isEmpty()) base else base + conditions.joinToString("") { "\u0000$it" }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
