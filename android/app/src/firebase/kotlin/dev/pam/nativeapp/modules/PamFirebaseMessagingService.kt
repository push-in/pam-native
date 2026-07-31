package dev.pam.nativeapp.modules

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.pam.nativeapp.push.BackgroundPush
import org.json.JSONObject

/**
 * Optional zero-glue Firebase entry point. It is compiled only when the Pam
 * project root contains .pam/google-services.json or google-services.json.
 */
public class PamFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        PamPushNotifications.attach(applicationContext)
        val data = JSONObject()
        message.data.forEach { (key, value) -> data.put(key, value) }
        val notification = message.notification
        val id = message.messageId
            ?: message.data["notification_id"]
            ?: message.data["id"]
            ?: "firebase-${System.currentTimeMillis()}"
        val deepLink = message.data["deep_link"]
            ?: message.data["deepLink"]
            ?: message.data["url"]
            ?: ""
        val title = notification?.title ?: message.data["title"].orEmpty()
        val body = notification?.body ?: message.data["body"].orEmpty()
        val dataJson = data.toString()
        PamPushNotifications.reportReceived(
            id = id,
            title = title,
            body = body,
            dataJson = dataJson,
            deepLink = deepLink,
        )
        sendBroadcast(
            BackgroundPush.receivedIntent(
                context = applicationContext,
                id = id,
                title = title,
                body = body,
                dataJson = dataJson,
                deepLink = deepLink,
            ),
        )
    }
}
