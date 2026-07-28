package dev.pam.nativeapp.modules

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

internal class NotificationsModule(private val activity: PamActivity) : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "requestPermission" -> requestPermission(completion)
                "schedule" -> schedule(payload, completion)
                "cancel" -> cancel(payload, completion)
                "registerPush" -> registerPush(completion)
                "nextPushEvent" -> PamPushNotifications.next(completion)
                else -> error("Unknown notifications method $method")
            }
        }.onFailure { error ->
            completion.complete(ModuleResultStatus.FAILURE, (error.message ?: "Notification failed").toByteArray())
        }
    }

    override fun close() {
        main.removeCallbacksAndMessages(null)
        PamPushNotifications.close("Notifications module closed")
    }

    private fun registerPush(completion: ModuleCompletion) {
        val firebase = runCatching {
            Class.forName("com.google.firebase.messaging.FirebaseMessaging")
        }.getOrElse {
            error(
                "Push registration requires the host app to provide Firebase Messaging " +
                    "or a generated notifications module",
            )
        }
        val instance = firebase.getMethod("getInstance").invoke(null)
        val task = firebase.getMethod("getToken").invoke(instance)
        val started = System.currentTimeMillis()
        fun poll() {
            runCatching {
                val complete = task.javaClass.getMethod("isComplete").invoke(task) as Boolean
                if (!complete) {
                    require(System.currentTimeMillis() - started < 15_000) {
                        "Push token registration timed out"
                    }
                    main.postDelayed(::poll, 50)
                    return
                }
                val successful = task.javaClass.getMethod("isSuccessful").invoke(task) as Boolean
                require(successful) { "Firebase rejected push token registration" }
                val token = task.javaClass.getMethod("getResult").invoke(task) as? String
                require(!token.isNullOrBlank()) { "Firebase returned an empty push token" }
                completion.complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(
                        mapOf(
                            "token" to WireValue.Text(token),
                            "provider" to WireValue.Integer(1),
                        ),
                    ),
                )
            }.onFailure { error ->
                completion.complete(
                    ModuleResultStatus.FAILURE,
                    (error.message ?: "Push registration failed").toByteArray(),
                )
            }
        }
        main.post(::poll)
    }

    private fun requestPermission(completion: ModuleCompletion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            completion.permission(true)
            return
        }
        activity.requestPamPermission(Manifest.permission.POST_NOTIFICATIONS) {
            completion.permission(it)
        }
    }

    private fun schedule(payload: ByteArray, completion: ModuleCompletion) {
        val values = WireMap.decode(payload)
        val id = values.text("id")
        val intent = notificationIntent(activity, id, values)
        val delay = values.integer("delaySeconds", 0).coerceIn(0, 31_536_000)
        if (delay == 0L) {
            NotificationReceiver().onReceive(activity, intent)
        } else {
            val alarm = activity.getSystemService(AlarmManager::class.java)
            alarm.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delay * 1_000,
                pending(activity, id, intent),
            )
        }
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun cancel(payload: ByteArray, completion: ModuleCompletion) {
        val id = WireMap.decode(payload).text("id")
        val intent = Intent(activity, NotificationReceiver::class.java)
        activity.getSystemService(AlarmManager::class.java).cancel(pending(activity, id, intent))
        activity.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
    }

    private fun ModuleCompletion.permission(granted: Boolean) {
        complete(
            ModuleResultStatus.SUCCESS,
            WireMap.encode(mapOf("granted" to WireValue.Flag(granted))),
        )
    }

    private fun Map<String, WireValue>.text(key: String): String =
        (this[key] as? WireValue.Text)?.value ?: error("Missing text field $key")

    private fun Map<String, WireValue>.integer(key: String, fallback: Long): Long =
        (this[key] as? WireValue.Integer)?.value ?: fallback

    companion object {
        private fun pending(context: Context, id: String, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun notificationIntent(
            context: Context,
            id: String,
            values: Map<String, WireValue>,
        ): Intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("title", (values["title"] as? WireValue.Text)?.value.orEmpty())
            putExtra("body", (values["body"] as? WireValue.Text)?.value.orEmpty())
            putExtra("importance", ((values["importance"] as? WireValue.Integer)?.value ?: 2L).toInt())
            putExtra("data", (values["data"] as? WireValue.Text)?.value.orEmpty())
            putExtra("deepLink", (values["deepLink"] as? WireValue.Text)?.value.orEmpty())
        }
    }
}

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val importance = intent.getIntExtra("importance", 2).coerceIn(1, 4)
        val channelId = "pam-native-$importance"
        val nativeImportance = when (importance) {
            1 -> NotificationManager.IMPORTANCE_LOW
            3 -> NotificationManager.IMPORTANCE_HIGH
            4 -> NotificationManager.IMPORTANCE_MAX
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Pam notifications", nativeImportance),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra("pam.notification.opened", true)
            putExtra("pam.notification.id", intent.getStringExtra("id").orEmpty())
            putExtra("pam.notification.title", intent.getStringExtra("title").orEmpty())
            putExtra("pam.notification.body", intent.getStringExtra("body").orEmpty())
            putExtra("pam.notification.data", intent.getStringExtra("data").orEmpty())
            putExtra("pam.notification.deepLink", intent.getStringExtra("deepLink").orEmpty())
        }
        val content = launch?.let {
            PendingIntent.getActivity(
                context,
                intent.getStringExtra("id").orEmpty().hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(dev.pam.nativeapp.R.drawable.pam_icon)
            .setContentTitle(intent.getStringExtra("title").orEmpty())
            .setContentText(intent.getStringExtra("body").orEmpty())
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        manager.notify(intent.getStringExtra("id").orEmpty().hashCode(), notification)
    }
}
