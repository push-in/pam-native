package dev.pam.nativeapp.modules

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpUploadInstrumentedTest {
    @Test fun streamsBinaryFileBeyondPhpBodyLimitAndCleansSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "http-upload-test-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(directory, "files").apply { mkdir() }
        val cache = File(directory, "snapshots").apply { mkdir() }
        val source = File(root, "binary.dat")
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        val expectedHash = MessageDigest.getInstance("SHA-256")
        source.outputStream().use { output -> repeat(32) { output.write(block); expectedHash.update(block) } }
        val length = source.length()
        val module = HttpModule(root, cache)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val failure = AtomicReference<Throwable?>()
                val receivedHash = AtomicReference<ByteArray?>()
                val serving = thread(name = "pam-http-upload-test") {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream()
                            assertEquals("PUT /upload HTTP/1.1", line(input))
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = line(input)
                                if (line.isEmpty()) break
                                val split = line.indexOf(':')
                                require(split > 0)
                                headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
                            }
                            assertEquals(length.toString(), headers["content-length"])
                            assertEquals("application/octet-stream", headers["content-type"])
                            assertNull(headers["transfer-encoding"])
                            // The network must keep using the private snapshot after source replacement.
                            source.writeText("changed after upload started")
                            var remaining = length
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                require(count > 0) { "Upload ended early" }
                                digest.update(buffer, 0, count)
                                remaining -= count
                            }
                            receivedHash.set(digest.digest())
                            socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    } catch (error: Throwable) { failure.set(error) }
                }
                val completed = CountDownLatch(1)
                var result: ModuleResultStatus? = null
                var payload = ByteArray(0)
                module.invoke("upload", WireMap.encode(mapOf(
                    "url" to WireValue.Text("http://127.0.0.1:${server.localPort}/upload"),
                    "path" to WireValue.Text(source.name),
                    "headers" to WireValue.Text("""{"Content-Type":"application/octet-stream"}"""),
                    "timeoutMs" to WireValue.Integer(15_000),
                ))) { status, response -> result = status; payload = response; completed.countDown() }
                assertTrue("Upload timed out", completed.await(20, TimeUnit.SECONDS))
                serving.join(5_000)
                assertFalse("Server did not stop", serving.isAlive)
                assertNull(failure.get())
                assertEquals(ModuleResultStatus.SUCCESS, result)
                assertEquals(WireValue.Integer(204), WireMap.decode(payload)["statusCode"])
                assertArrayEquals(expectedHash.digest(), receivedHash.get())
                assertTrue("Snapshot leaked", cache.listFiles().isNullOrEmpty())
                assertEquals("changed after upload started", source.readText())
            }
        } finally { module.close(); directory.deleteRecursively() }
    }

    private fun line(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            require(value >= 0 && bytes.size() < 16_384) { "Incomplete or oversized HTTP header" }
            if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            bytes.write(value)
        }
    }
}
