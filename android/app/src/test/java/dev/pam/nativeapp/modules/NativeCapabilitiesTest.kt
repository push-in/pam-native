package dev.pam.nativeapp.modules

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCapabilitiesTest {
    @Test
    fun multipartBodyDeclaresExactlyTheBytesItStreams() {
        val directory = Files.createTempDirectory("pam-multipart").toFile()
        try {
            val file = File(directory, "photo.jpg").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
            val body = MultipartBody(
                listOf(
                    MultipartPart.Field("caption", "Olá \"mundo\""),
                    MultipartPart.FilePart("media", file, "photo.jpg", "image/jpeg"),
                ),
                boundary = "pam-test",
            )
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()
            body.writeTo(output, { false }) { progress += it }
            val bytes = output.toByteArray()
            assertEquals(body.contentLength(), bytes.size.toLong())
            assertEquals(bytes.size.toLong(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b > a })
            val text = String(bytes, Charsets.ISO_8859_1)
            assertTrue(text.startsWith("--pam-test\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n"))
            assertTrue(text.contains("name=\"media\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
            assertTrue(text.endsWith("\r\n--pam-test--\r\n"))
            assertEquals("multipart/form-data; boundary=pam-test", body.contentType)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun multipartBodyStopsWhenCancelledOrWhenTheFileChanges() {
        val directory = Files.createTempDirectory("pam-multipart-cancel").toFile()
        try {
            val file = File(directory, "clip.mp4").apply { writeBytes(ByteArray(10)) }
            val body = MultipartBody(listOf(MultipartPart.FilePart("f", file, "clip.mp4", "video/mp4")))
            assertThrows(IllegalStateException::class.java) {
                body.writeTo(ByteArrayOutputStream(), { true }) {}
            }
            file.appendBytes(ByteArray(5))
            assertThrows(IllegalArgumentException::class.java) {
                body.writeTo(ByteArrayOutputStream(), { false }) {}
            }
            assertThrows(IllegalArgumentException::class.java) {
                MultipartBody(listOf(MultipartPart.Field("bad\r\nname", "x")))
            }
            assertThrows(IllegalArgumentException::class.java) {
                MultipartBody(listOf(MultipartPart.FilePart("f", file, "../x", "video/mp4")))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun progressThrottleReportsStartStepsAndCompletion() {
        val throttle = TransferProgressThrottle(10L * 1024 * 1024)
        assertTrue(throttle.shouldReport(1))
        assertFalse(throttle.shouldReport(1_000))
        assertTrue(throttle.shouldReport(1 + 104_858 + 256 * 1024))
        assertTrue(throttle.shouldReport(10L * 1024 * 1024))
        assertFalse(throttle.shouldReport(10L * 1024 * 1024))
    }

    @Test
    fun privateFileTransfersMoveCopyAndRefuseAccidentalOverwrite() {
        val root = Files.createTempDirectory("pam-files").toFile().canonicalFile
        try {
            val source = File(root, "a.txt").apply { writeText("one") }
            val copy = File(root, "copies/b.txt")
            transferPrivateFile(source, copy, move = false, overwrite = false)
            assertEquals("one", copy.readText())
            assertTrue(source.isFile)
            assertThrows(IllegalArgumentException::class.java) {
                transferPrivateFile(source, copy, move = false, overwrite = false)
            }
            source.writeText("two")
            transferPrivateFile(source, copy, move = true, overwrite = true)
            assertEquals("two", copy.readText())
            assertFalse(source.exists())
            val directory = File(root, "dir").apply { mkdirs(); File(this, "x").writeText("x") }
            assertThrows(IllegalArgumentException::class.java) {
                transferPrivateFile(directory, File(directory, "inner/dir"), move = true, overwrite = false)
            }
            transferPrivateFile(directory, File(root, "moved"), move = true, overwrite = false)
            assertEquals("x", File(root, "moved/x").readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.contains(".tmp-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedMimeTypeIsTheNarrowestCommonType() {
        assertEquals("image/jpeg", sharedMimeType(listOf("image/jpeg", "image/jpeg")))
        assertEquals("image/*", sharedMimeType(listOf("image/jpeg", "image/png")))
        assertEquals("*/*", sharedMimeType(listOf("image/jpeg", "video/mp4")))
        assertEquals("*/*", sharedMimeType(emptyList()))
    }

    @Test
    fun privateFileResolutionStaysInsideTheSandbox() {
        val root = Files.createTempDirectory("pam-resolve").toFile()
        try {
            File(root, "a/b.txt").apply { parentFile?.mkdirs(); writeText("x") }
            assertEquals("b.txt", resolvePrivateFile(root, "a/b.txt").name)
            listOf("../b.txt", "/etc/passwd", "a//b.txt", "a/./b.txt", "missing.txt").forEach { path ->
                assertThrows(IllegalArgumentException::class.java) { resolvePrivateFile(root, path) }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun replyActionIdsArePositiveTimeOrderedIntegers() {
        val first = ReplyFailure.createActionId(1_700_000_000_000).toLong()
        val later = ReplyFailure.createActionId(1_700_000_000_001).toLong()
        assertTrue(first in 1_700_000_000_000_000..1_700_000_000_000_999)
        assertTrue(later > first)
        assertTrue(later < 9_007_199_254_740_991)
        assertTrue(NotificationTemplate.complete("{action_id}", emptyMap()))
    }

    @Test
    fun notificationTemplatesResolveDataAndEncodeUrls() {
        val variables = mapOf("chat_id" to "42 a/b", "reply" to "Oi")
        assertEquals(
            "https://api.test/chats/42%20a%2Fb/messages",
            NotificationTemplate.render("https://api.test/chats/{chat_id}/messages", variables) {
                java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            },
        )
        assertEquals("Oi ", NotificationTemplate.render("{reply} {missing}", variables))
        assertTrue(NotificationTemplate.complete("{chat_id}/{uuid}", variables))
        assertFalse(NotificationTemplate.complete("{unknown}", variables))
    }

    @Test
    fun credentialPlaceholdersResolveThePushAccountToken() {
        val tokens = mapOf("user-a" to "token-a", "user-b" to "token-b")
        val lookup: (String) -> String? = { tokens[PamNotificationCredentials.normalize(it)] }
        val pushA = mapOf("user_id" to " USER-A ", "recipient_user_id" to "user-b")
        assertEquals("user-a", PamNotificationCredentials.normalize(NotificationTemplate.credentialAccount("credential:user_id|recipient_user_id", pushA)))
        assertEquals(
            "Bearer token-a",
            NotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", pushA, null, lookup),
        )
        val legacy = mapOf("recipient_user_id" to "user-b")
        assertEquals(
            "Bearer token-b",
            NotificationTemplate.render("Bearer {credential:user_id|recipient_user_id}", legacy, null, lookup),
        )
        assertFalse(NotificationTemplate.missingCredential("Bearer {credential:user_id}", pushA, lookup))
        assertTrue(NotificationTemplate.missingCredential("Bearer {credential:user_id}", mapOf("user_id" to "user-c"), lookup))
        assertTrue(NotificationTemplate.missingCredential("Bearer {credential:user_id}", emptyMap(), lookup))
        assertFalse(NotificationTemplate.missingCredential("Bearer {storage:auth.token}", emptyMap(), lookup))
        assertEquals("Bearer ", NotificationTemplate.render("Bearer {credential:user_id}", mapOf("user_id" to "user-c"), null, lookup))
        assertTrue(NotificationTemplate.complete("Bearer {credential:user_id|recipient_user_id}", emptyMap()))
    }

    @Test
    fun credentialSlotsHashTheNormalizedAccount() {
        val slot = PamNotificationCredentials.slot(PamNotificationCredentials.normalize(" User-A "))
        assertEquals(64, slot.length)
        assertEquals(slot, PamNotificationCredentials.slot("user-a"))
        assertFalse(slot.contains("user"))
        assertFalse(slot == PamNotificationCredentials.slot("user-b"))
    }

    @Test
    fun pushTimestampsAcceptSecondsMillisecondsAndIso8601() {
        assertEquals(1_700_000_000_000, PamPushRendering.timestamp("1700000000"))
        assertEquals(1_700_000_000_000, PamPushRendering.timestamp("1700000000000"))
        assertEquals(1_700_000_000_500, PamPushRendering.timestamp("1700000000.5"))
        assertEquals(0L, PamPushRendering.timestamp("1970-01-01T00:00:00Z"))
    }

    @Test
    fun prefetchTasksRunByPriorityThenFifo() {
        val tasks = listOf(
            ImagePrefetchModule.PrefetchTask(2, 1) {},
            ImagePrefetchModule.PrefetchTask(5, 2) {},
            ImagePrefetchModule.PrefetchTask(2, 3) {},
            ImagePrefetchModule.PrefetchTask(1, 4) {},
        ).sorted()
        assertArrayEquals(longArrayOf(2, 1, 3, 4), tasks.map { it.order }.toLongArray())
    }

    @Test
    fun mediaAlbumAndFileNamesAreSanitized() {
        assertEquals("Zé Chat", MediaStoreSaver.normalizedAlbum(" Zé Chat "))
        assertEquals(null, MediaStoreSaver.normalizedAlbum(""))
        assertThrows(IllegalArgumentException::class.java) { MediaStoreSaver.normalizedAlbum("../x") }
        assertEquals("a_b.jpg", MediaStoreSaver.normalizedDisplayName("a/b.jpg"))
    }
}
