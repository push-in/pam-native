package dev.pam.nativeapp.modules

import com.sun.net.httpserver.HttpServer
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** maxResponseBytes: 1 MiB default, per-request override, file spill above the bridge size. */
class HttpResponseLimitTest {
    private val body = ByteArray(1_200_000) { (it % 26 + 'a'.code).toByte() }

    private fun call(module: HttpModule, port: Int, limit: Long?): Pair<ModuleResultStatus, ByteArray> {
        val values = linkedMapOf<String, WireValue>(
            "url" to WireValue.Text("http://127.0.0.1:$port/page"),
            "method" to WireValue.Text("GET"),
            "headers" to WireValue.Text("{}"),
            "timeoutMs" to WireValue.Integer(10_000),
        )
        if (limit != null) values["maxResponseBytes"] = WireValue.Integer(limit)
        val done = CountDownLatch(1)
        var result: Pair<ModuleResultStatus, ByteArray>? = null
        module.invoke("request", WireMap.encode(values)) { status, payload ->
            result = status to payload
            done.countDown()
        }
        assertTrue("request finished", done.await(20, TimeUnit.SECONDS))
        return result!!
    }

    @Test
    fun largeResponsesNeedAHigherLimitAndArriveThroughAPrivateFile() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/page") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val root = Files.createTempDirectory("pam-http-limit").toFile()
        val module = HttpModule(File(root, "files").apply { mkdirs() }, File(root, "uploads").apply { mkdirs() })
        try {
            val port = server.address.port
            val (refused, message) = call(module, port, null)
            assertEquals(ModuleResultStatus.FAILURE, refused)
            assertTrue(String(message), String(message).contains("raise maxResponseBytes"))

            val (status, payload) = call(module, port, 2L * 1024 * 1024)
            assertEquals(ModuleResultStatus.SUCCESS, status)
            val values = WireMap.decode(payload)
            val path = (values["bodyFile"] as WireValue.Text).value
            assertTrue(path.endsWith(".pamhttp"))
            assertTrue(File(path).readBytes().contentEquals(body))

            val (tooHigh, _) = call(module, port, 32L * 1024 * 1024)
            assertEquals(ModuleResultStatus.FAILURE, tooHigh)
        } finally {
            module.close()
            server.stop(0)
            root.deleteRecursively()
        }
    }
}
