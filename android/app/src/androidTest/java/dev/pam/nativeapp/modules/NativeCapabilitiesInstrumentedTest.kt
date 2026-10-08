package dev.pam.nativeapp.modules

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.CapabilityTestActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCapabilitiesInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun multipartTransferStreamsFieldsAndFilesWithProgressAndHeaders() {
        val directory = File(context.cacheDir, "multipart-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(directory, "files").apply { mkdirs() }
        File(root, "uploads").mkdirs()
        val payload = ByteArray(3 * 1024 * 1024) { (it % 253).toByte() }
        File(root, "uploads/clip.bin").writeBytes(payload)
        val module = HttpModule(root, File(directory, "cache"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nDate: Mon, 05 Oct 2026 12:00:00 GMT\r\nContent-Length: 2\r\n\r\nok").use { server ->
                val parts = JSONArray()
                    .put(JSONObject().put("type", 1).put("name", "caption").put("value", "Olá"))
                    .put(JSONObject().put("type", 2).put("name", "media").put("path", "uploads/clip.bin").put("mimeType", "application/octet-stream").put("filename", "clip.bin"))
                val transfer = call(module, "transferStart", mapOf(
                    "kind" to WireValue.Integer(1),
                    "url" to WireValue.Text("http://127.0.0.1:${server.port}/media"),
                    "method" to WireValue.Text("POST"),
                    "headers" to WireValue.Text("""{"Authorization":"Bearer t"}"""),
                    "parts" to WireValue.Text(parts.toString()),
                    "timeoutMs" to WireValue.Integer(20_000),
                ))["transfer"] as WireValue.Integer
                val progress = mutableListOf<Long>()
                var complete: Map<String, WireValue>? = null
                while (complete == null) {
                    val event = call(module, "transferNext", mapOf("transfer" to transfer))
                    when ((event["state"] as WireValue.Integer).value) {
                        1L -> progress += (event["bytesSent"] as WireValue.Integer).value
                        2L -> complete = event
                        else -> error("Transfer failed: ${event["message"]}")
                    }
                }
                assertEquals(201L, (complete["statusCode"] as WireValue.Integer).value)
                assertEquals("ok", (complete["body"] as WireValue.Text).value)
                assertTrue((complete["headers"] as WireValue.Text).value.contains("\"date\":\"Mon, 05 Oct 2026 12:00:00 GMT\""))
                assertTrue("progress must increase", progress.zipWithNext().all { (a, b) -> b >= a })
                assertTrue("progress must report intermediate steps", progress.size >= 3)
                val request = server.request()
                assertTrue(request.head.startsWith("POST /media HTTP/1.1"))
                assertTrue(request.head.contains("Authorization: Bearer t"))
                assertTrue(request.head.contains("Content-Type: multipart/form-data; boundary=pam-"))
                val body = String(request.body, Charsets.ISO_8859_1)
                assertTrue(body.contains("name=\"caption\"\r\n\r\nOl"))
                assertTrue(body.contains("filename=\"clip.bin\""))
                assertEquals(progress.last(), request.body.size.toLong())
                call(module, "transferCancel", mapOf("transfer" to transfer))
            }
        } finally {
            module.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun fileUploadTransferReportsProgressAndCleansSnapshot() {
        val directory = File(context.cacheDir, "upload-progress-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(directory, "files").apply { mkdirs() }
        val cache = File(directory, "cache").apply { mkdirs() }
        File(root, "doc.bin").writeBytes(ByteArray(1024 * 1024) { 7 })
        val module = HttpModule(root, cache)
        try {
            LocalServer(response = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n").use { server ->
                val transfer = call(module, "transferStart", mapOf(
                    "kind" to WireValue.Integer(2),
                    "url" to WireValue.Text("http://127.0.0.1:${server.port}/put"),
                    "method" to WireValue.Text("PUT"),
                    "path" to WireValue.Text("doc.bin"),
                    "headers" to WireValue.Text("{}"),
                ))["transfer"] as WireValue.Integer
                var last = 0L
                while (true) {
                    val event = call(module, "transferNext", mapOf("transfer" to transfer))
                    val state = (event["state"] as WireValue.Integer).value
                    if (state == 1L) last = (event["bytesSent"] as WireValue.Integer).value
                    if (state == 2L) {
                        assertEquals(204L, (event["statusCode"] as WireValue.Integer).value)
                        break
                    }
                    check(state != 3L) { "Upload failed: ${event["message"]}" }
                }
                assertEquals(1024L * 1024, last)
                assertEquals(1024 * 1024, server.request().body.size)
                Thread.sleep(200)
                assertTrue("snapshot leaked", cache.listFiles().isNullOrEmpty())
            }
        } finally {
            module.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun shareIntentGrantsEveryFileAndUsesSendMultiple() {
        val uris = listOf(Uri.parse("content://a/1"), Uri.parse("content://a/2"))
        val intent = shareFilesIntent(uris, "image/*")
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals(2, intent.clipData?.itemCount)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(Intent.ACTION_SEND, shareFilesIntent(uris.take(1), "image/png").action)
    }

    @Test
    fun mediaStoreSaverPublishesImagesToTheSharedLibrary() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val source = File(context.cacheDir, "pam-save-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, source.outputStream())
        val saved = MediaStoreSaver(context).save(source, "image/png", "PAM Tests", source.name)
        val uri = Uri.parse(saved.getString("uri"))
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.IS_PENDING), null, null, null).use { cursor ->
                assertNotNull(cursor)
                assertTrue(cursor!!.moveToFirst())
                assertEquals("Pictures/PAM Tests/", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            assertEquals("PAM Tests", saved.getString("albumTitle"))
        } finally {
            context.contentResolver.delete(uri, null, null)
            source.delete()
        }
    }

    @Test
    fun windowModuleTogglesFlagSecure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CapabilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CapabilityTestActivity
        try {
            val module = WindowModule(activity)
            val enabled = call(module, "secure", mapOf("enabled" to WireValue.Flag(true)))
            assertEquals(WireValue.Flag(true), enabled["enabled"])
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            call(module, "secure", mapOf("enabled" to WireValue.Flag(false)))
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun accessibilityAndDeviceProfilesAreReported() {
        val accessibility = AccessibilityModule(context)
        val status = call(accessibility, "status", emptyMap())
        assertTrue(status["enabled"] is WireValue.Flag)
        val announced = call(accessibility, "announce", mapOf("text" to WireValue.Text("Mensagem enviada")))
        assertTrue(announced["delivered"] is WireValue.Flag)
        val system = SystemModule(context)
        val latch = CountDownLatch(1)
        val result = AtomicReference<ByteArray>()
        system.invoke(NativeOperation.DEVICE_INFO, ByteArray(0)) { _, bytes -> result.set(bytes); latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val values = WireMap.decode(result.get())
        assertTrue(((values["memoryClassMb"] as WireValue.Integer).value) > 0)
        assertTrue(values["lowRamDevice"] is WireValue.Flag)
        assertTrue(values["powerSaveMode"] is WireValue.Flag)
        system.close()
    }

    @Test
    fun conversationNotificationsUseMessagingStyleRepliesAndHistory() {
        val key = "chat:${UUID.randomUUID()}"
        val first = ConversationSpec(
            key = key,
            title = "Viagem",
            group = true,
            self = ConversationPerson("Você"),
            messages = listOf(ConversationMessage("m1", "Partiu?", 1_000, ConversationPerson("Ana", key = "ana"))),
            replyLabel = "Responder",
            markReadLabel = "Marcar como lida",
        )
        try {
            PamConversationNotifications.show(context, first)
            val merged = PamConversationNotifications.show(
                context,
                first.copy(messages = listOf(
                    ConversationMessage("m1", "Partiu?", 1_000, ConversationPerson("Ana", key = "ana")),
                    ConversationMessage("m2", "Bora!", 2_000, ConversationPerson("Bia", key = "bia")),
                )),
            )
            assertEquals(listOf("m1", "m2"), merged.messages.map { it.id })
            val notification = PamConversationNotifications.build(context, merged).build()
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            assertNotNull(style)
            assertEquals("Viagem", style!!.conversationTitle)
            assertTrue(style.isGroupConversation)
            assertEquals(listOf("Partiu?", "Bora!"), style.messages.map { it.text.toString() })
            val reply = NotificationCompat.getAction(notification, 0)
            assertEquals("Responder", reply?.title)
            assertEquals(PamConversationNotifications.REMOTE_INPUT_KEY, reply?.remoteInputs?.single()?.resultKey)
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, NotificationCompat.getAction(notification, 1)?.semanticAction)
            val updated = PamConversationNotifications.appendOwnReply(context, key, "Já vou")
            assertNull(updated?.messages?.last()?.sender)
            assertEquals("Já vou", updated?.messages?.last()?.text)
        } finally {
            PamConversationNotifications.cancel(context, key)
        }
        assertNull(PamConversationNotifications.load(context, key))
    }

    @Test
    fun inlineReplyIsSentNativelyAndQueuedForPhp() {
        val key = "chat-${UUID.randomUUID()}"
        context.getSharedPreferences("pam-native", Context.MODE_PRIVATE).edit().putString("auth.token", "secret").commit()
        LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
            PamConversationNotifications.show(
                context,
                ConversationSpec(
                    key = key,
                    messages = listOf(ConversationMessage("m1", "Oi", 1_000, ConversationPerson("Ana"))),
                    replyLabel = "Responder",
                    replyEndpoint = JSONObject()
                        .put("method", "POST")
                        .put("url", "http://127.0.0.1:${server.port}/chats/{chat_id}/messages")
                        .put("headers", JSONObject().put("Authorization", "Bearer {storage:auth.token}"))
                        .put("body", JSONObject().put("body", "{reply}").put("conversation", "{conversation}")),
                    dataJson = """{"chat_id":"42"}""",
                ),
            )
            val intent = Intent(context, PamNotificationActionReceiver::class.java)
                .setAction(PamConversationNotifications.ACTION_REPLY)
                .putExtra(PamConversationNotifications.EXTRA_KEY, key)
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(PamConversationNotifications.REMOTE_INPUT_KEY).build()),
                intent,
                android.os.Bundle().apply { putCharSequence(PamConversationNotifications.REMOTE_INPUT_KEY, "Já vou") },
            )
            PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Já vou")
            val request = server.request()
            assertTrue(request.head.startsWith("POST /chats/42/messages HTTP/1.1"))
            assertTrue(request.head.contains("Authorization: Bearer secret"))
            assertEquals("Já vou", JSONObject(String(request.body, Charsets.UTF_8)).getString("body"))
            val event = AtomicReference<Map<String, WireValue>>()
            val latch = CountDownLatch(1)
            var drained = false
            while (!drained) {
                val next = CountDownLatch(1)
                PamNotificationActions.next { _, payload ->
                    val values = WireMap.decode(payload)
                    if ((values["conversation"] as? WireValue.Text)?.value == key) {
                        event.set(values)
                        latch.countDown()
                        drained = true
                    }
                    next.countDown()
                }
                assertTrue(next.await(5, TimeUnit.SECONDS))
            }
            assertTrue(latch.await(1, TimeUnit.SECONDS))
            assertEquals(WireValue.Text("Já vou"), event.get()["text"])
            assertEquals(WireValue.Integer(201), event.get()["statusCode"])
            assertEquals(WireValue.Flag(true), event.get()["handledNatively"])
        }
        PamConversationNotifications.cancel(context, key)
    }

    @Test
    fun secureStorageSealsValuesWithTheKeystore() {
        PamSecureStore.delete(context, "app-lock:user-a")
        assertTrue(PamSecureStore.set(context, "app-lock:user-a", "{\"pinHash\":\"secret\"}"))
        val raw = context.getSharedPreferences("pam-native-secure-storage", Context.MODE_PRIVATE).all
        raw.forEach { (slot, value) ->
            assertFalse(slot.contains("app-lock"))
            assertFalse((value as String).contains("secret"))
        }
        assertEquals("{\"pinHash\":\"secret\"}", PamSecureStore.get(context, "app-lock:user-a"))
        val module = SecureStorageModule(context)
        val read = AtomicReference<ByteArray>()
        module.invoke("get", WireMap.encode(mapOf("key" to WireValue.Text("app-lock:user-a")))) { status, payload ->
            assertEquals(ModuleResultStatus.SUCCESS, status)
            read.set(payload)
        }
        assertEquals(WireValue.Flag(true), WireMap.decode(read.get())["found"])
        PamSecureStore.delete(context, "app-lock:user-a")
        assertNull(PamSecureStore.get(context, "app-lock:user-a"))
    }

    @Test
    fun textScaleIsPersistedForTheNextLaunch() {
        val previous = dev.pam.nativeapp.PamTextScale.stored(context)
        try {
            val module = AccessibilityModule(context)
            val answer = AtomicReference<ByteArray>()
            module.invoke(
                "setTextScale",
                WireMap.encode(mapOf("multiplier" to WireValue.Decimal(1.3), "maxSystemScale" to WireValue.Decimal(1.6))),
            ) { status, payload ->
                assertEquals(ModuleResultStatus.SUCCESS, status)
                answer.set(payload)
            }
            val values = WireMap.decode(answer.get())
            assertEquals(1.3, (values["multiplier"] as WireValue.Decimal).value, 1e-6)
            assertEquals(1.6, (values["maxSystemScale"] as WireValue.Decimal).value, 1e-6)
            assertEquals(1.3f to 1.6f, dev.pam.nativeapp.PamTextScale.stored(context))
            val rejected = AtomicReference<ModuleResultStatus>()
            module.invoke("setTextScale", WireMap.encode(mapOf("multiplier" to WireValue.Decimal(9.0)))) { status, _ ->
                rejected.set(status)
            }
            assertEquals(ModuleResultStatus.FAILURE, rejected.get())
        } finally {
            dev.pam.nativeapp.PamTextScale.persist(context, previous.first, previous.second)
        }
    }

    @Test
    fun biometricsStatusMatchesAvailability() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CapabilityTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CapabilityTestActivity
        try {
            val values = call(BiometricsModule(activity), "status", emptyMap())
            val kind = (values["kind"] as WireValue.Integer).value
            assertTrue(kind in BiometricsModule.KIND_NONE.toLong()..BiometricsModule.KIND_OTHER.toLong())
            assertEquals(kind != BiometricsModule.KIND_NONE.toLong(), (values["available"] as WireValue.Flag).value)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun notificationCredentialsAreKeystoreSealedPerAccount() {
        PamNotificationCredentials.clear(context)
        PamNotificationCredentials.set(context, " User-A ", "token-a")
        PamNotificationCredentials.set(context, "user-b", "token-b")
        val raw = context.getSharedPreferences("pam-native-notification-credentials", Context.MODE_PRIVATE).all
        assertEquals(2, raw.size)
        raw.forEach { (slot, value) ->
            assertFalse(slot.contains("user"))
            assertFalse((value as String).contains("token-"))
        }
        assertEquals("token-a", PamNotificationCredentials.token(context, "USER-A"))
        assertEquals("token-b", PamNotificationCredentials.token(context, "user-b"))
        PamNotificationCredentials.remove(context, "user-a")
        assertNull(PamNotificationCredentials.token(context, "user-a"))
        PamNotificationCredentials.replace(context, mapOf("user-c" to "token-c"))
        assertNull(PamNotificationCredentials.token(context, "user-b"))
        assertEquals("token-c", PamNotificationCredentials.token(context, "user-c"))
        PamNotificationCredentials.clear(context)
        assertNull(PamNotificationCredentials.token(context, "user-c"))
    }

    @Test
    fun inlineReplyIsSentWithThePushAccountCredential() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a", "user-b" to "token-b"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                PamConversationNotifications.show(context, accountConversation(key, "http://127.0.0.1:${server.port}", "USER-B"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Já vou")
                val request = server.request()
                assertTrue(request.head.startsWith("POST /chats/42/messages HTTP/1.1"))
                assertTrue(request.head.contains("Authorization: Bearer token-b"))
                assertFalse(request.head.contains("token-a"))
                assertFalse(String(request.body, Charsets.UTF_8).contains("token-"))
                val event = drainAction(key)
                assertEquals(WireValue.Integer(201), event["statusCode"])
                assertEquals(WireValue.Flag(true), event["handledNatively"])
                assertEquals(WireValue.Flag(false), event["credentialMissing"])
                assertNotNull(PamConversationNotifications.load(context, key))
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    @Test
    fun actionOfAnAccountWithoutCredentialIsDismissedWithoutRequest() {
        val key = "chat-${UUID.randomUUID()}"
        PamNotificationCredentials.replace(context, mapOf("user-a" to "token-a"))
        try {
            LocalServer(response = "HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n").use { server ->
                PamConversationNotifications.show(context, accountConversation(key, "http://127.0.0.1:${server.port}", "user-b"))
                PamNotificationActions.handle(context, NotificationActionType.REPLY, key, "Não deve sair")
                val event = drainAction(key)
                assertEquals(WireValue.Flag(false), event["handledNatively"])
                assertEquals(WireValue.Flag(true), event["credentialMissing"])
                assertNull(PamConversationNotifications.load(context, key))
                assertNull(server.requestOrNull(500))
            }
        } finally {
            PamConversationNotifications.cancel(context, key)
            PamNotificationCredentials.clear(context)
        }
    }

    private fun accountConversation(key: String, base: String, account: String) = ConversationSpec(
        key = key,
        messages = listOf(ConversationMessage("m1", "Oi", 1_000, ConversationPerson("Ana"))),
        replyLabel = "Responder",
        markReadLabel = "Marcar como lida",
        replyEndpoint = JSONObject()
            .put("method", "POST")
            .put("url", "$base/chats/{chat_id}/messages")
            .put("headers", JSONObject().put("Authorization", "Bearer {credential:user_id|recipient_user_id}"))
            .put("body", JSONObject().put("body", "{reply}")),
        dataJson = """{"chat_id":"42","user_id":"$account"}""",
    )

    private fun drainAction(key: String): Map<String, WireValue> {
        val event = AtomicReference<Map<String, WireValue>>()
        while (event.get() == null) {
            val next = CountDownLatch(1)
            PamNotificationActions.next { _, payload ->
                val values = WireMap.decode(payload)
                if ((values["conversation"] as? WireValue.Text)?.value == key) event.set(values)
                next.countDown()
            }
            assertTrue(next.await(5, TimeUnit.SECONDS))
        }
        return event.get()
    }

    @Test
    fun pushRenderingRulesRenderSuppressAndDismissConversations() {
        val rule = JSONObject()
            .put("type", "chat.message")
            .put("field", "type")
            .put("kind", PamPushRendering.KIND_CONVERSATION)
            .put("conversation", JSONObject().put("key", "{chat_id}").put("title", "{chat_title}").put("group", "{is_group}"))
            .put("message", JSONObject().put("sender", "{sender_name}").put("text", "{body}").put("timestamp", "{sent_at}").put("id", "{message_id}"))
            .put("reply", JSONObject().put("label", "Responder"))
            .put("suppress", JSONArray().put(JSONObject().put("path", "chat/{chat_id}")))
        PamPushRendering.register(context, rule.toString())
        PamPushRendering.register(
            context,
            JSONObject().put("type", "chat.read").put("kind", PamPushRendering.KIND_DISMISS)
                .put("conversation", JSONObject().put("key", "{chat_id}")).toString(),
        )
        val chat = "c${UUID.randomUUID().toString().take(8)}"
        val data = JSONObject().put("type", "chat.message").put("chat_id", chat).put("chat_title", "Grupo")
            .put("is_group", "1").put("sender_name", "Ana").put("body", "Oi").put("sent_at", "1700000000").put("message_id", "m1")
        try {
            PamActiveRoute.setForeground(false)
            assertTrue(PamPushRendering.render(context, "p1", "", "", data.toString()))
            val stored = PamConversationNotifications.load(context, chat)
            assertNotNull(stored)
            assertTrue(stored!!.group)
            assertEquals(1_700_000_000_000, stored.messages.single().timestamp)

            PamActiveRoute.update("chat", """{"chatId":"$chat"}""", "/chat/$chat")
            PamActiveRoute.setForeground(true)
            assertTrue(PamPushRendering.render(context, "p2", "", "", data.put("message_id", "m2").toString()))
            assertEquals(1, PamConversationNotifications.load(context, chat)!!.messages.size)

            assertTrue(PamPushRendering.render(context, "p3", "", "", JSONObject().put("type", "chat.read").put("chat_id", chat).toString()))
            assertNull(PamConversationNotifications.load(context, chat))
            assertFalse(PamPushRendering.render(context, "p4", "", "", JSONObject().put("type", "other").toString()))
        } finally {
            PamActiveRoute.setForeground(false)
            PamActiveRoute.update("", "{}", "")
            PamPushRendering.clear(context)
            PamConversationNotifications.cancel(context, chat)
        }
    }

    @Test
    fun imagePrefetchWarmsTheRendererDiskCache() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        LocalServer(response = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n\r\n", body = png).use { server ->
            val url = "http://127.0.0.1:${server.port}/avatar-${UUID.randomUUID()}.png"
            val module = ImagePrefetchModule(context)
            try {
                val result = call(module, "prefetch", mapOf(
                    "urls" to WireValue.Text(JSONArray().put(url).toString()),
                    "priority" to WireValue.Integer(4),
                ))
                assertEquals(WireValue.Integer(1), result["succeeded"])
                assertEquals(WireValue.Integer(png.size.toLong()), result["bytes"])
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val cached = File(File(context.cacheDir, "pam-images-v1"), "$digest.image")
                assertTrue(cached.isFile)
                assertNotNull(PamConversationNotifications.avatar(context, url))
                cached.delete()
            } finally {
                module.close()
            }
        }
    }

    @Test
    fun cancelledTimersNeverFire() {
        val module = TimersModule()
        val fired = CountDownLatch(1)
        val statuses = mutableListOf<ModuleResultStatus>()
        module.invoke("after", WireMap.encode(mapOf("milliseconds" to WireValue.Integer(300), "timer" to WireValue.Integer(7)))) { status, _ ->
            synchronized(statuses) { statuses += status }
            fired.countDown()
        }
        call(module, "cancel", mapOf("timer" to WireValue.Integer(7)))
        assertTrue(fired.await(2, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertEquals(listOf(ModuleResultStatus.FAILURE), synchronized(statuses) { statuses.toList() })
        module.close()
    }

    private fun call(module: NativeModule, method: String, values: Map<String, WireValue>): Map<String, WireValue> {
        val latch = CountDownLatch(1)
        val status = AtomicReference<ModuleResultStatus>()
        val payload = AtomicReference<ByteArray>()
        module.invoke(method, WireMap.encode(values)) { resultStatus, bytes ->
            status.set(resultStatus)
            payload.set(bytes)
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(20, TimeUnit.SECONDS))
        check(status.get() == ModuleResultStatus.SUCCESS) { "$method failed: ${String(payload.get())}" }
        return if (payload.get().isEmpty()) emptyMap() else WireMap.decode(payload.get())
    }

    private class Request(val head: String, val body: ByteArray)

    /** One-shot HTTP/1.1 server that records the request and replies with a fixed response. */
    private class LocalServer(response: String, body: ByteArray = ByteArray(0)) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 20_000 }
        private val received = AtomicReference<Request>()
        private val failure = AtomicReference<Throwable>()
        private val done = CountDownLatch(1)
        val port: Int = socket.localPort
        private val worker = thread(name = "pam-capability-test-server") {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 20_000
                    val input = client.getInputStream()
                    val head = StringBuilder()
                    var length = 0
                    while (true) {
                        val line = line(input)
                        if (line.isEmpty()) break
                        head.append(line).append("\r\n")
                        if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                    }
                    val requestBody = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(requestBody, read, length - read)
                        require(count > 0) { "Request ended early" }
                        read += count
                    }
                    received.set(Request(head.toString(), requestBody))
                    client.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }

        fun request(): Request {
            assertTrue("server did not finish", done.await(20, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            return received.get()
        }

        fun requestOrNull(timeoutMillis: Long): Request? {
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
            return received.get()
        }

        override fun close() {
            socket.close()
            worker.join(5_000)
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                require(value >= 0) { "Connection closed" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.write(value)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
    }
}
