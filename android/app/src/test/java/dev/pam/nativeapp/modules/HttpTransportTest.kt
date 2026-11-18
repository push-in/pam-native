package dev.pam.nativeapp.modules

import com.sun.net.httpserver.HttpServer
import dev.pam.nativeapp.net.PamHttpClient
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Happy Eyeballs and per-host fairness of the HTTP module transport. */
class HttpTransportTest {
    private fun server(delayMs: Map<String, Long> = emptyMap()): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/") { exchange ->
                Thread.sleep(delayMs[exchange.requestHeaders.getFirst("Host")?.substringBefore(':')] ?: 0)
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    private fun request(module: HttpModule, url: String, done: (ModuleResultStatus, Long) -> Unit) {
        val started = System.nanoTime()
        module.invoke(
            "request",
            WireMap.encode(
                mapOf(
                    "url" to WireValue.Text(url),
                    "method" to WireValue.Text("GET"),
                    "headers" to WireValue.Text("{}"),
                    "timeoutMs" to WireValue.Integer(20_000),
                ),
            ),
        ) { status, _ -> done(status, (System.nanoTime() - started) / 1_000_000) }
    }

    @Test
    fun aBlackholedFirstAddressFallsBackInAFractionOfASecond() {
        val server = server()
        // The first address never answers (like an AAAA record on a network
        // whose IPv6 is broken); the second is the working one.
        val dns = Dns { listOf(InetAddress.getByName("10.255.255.1"), InetAddress.getByName("127.0.0.1")) }
        val module = HttpModule(baseClient = PamHttpClient.shared.newBuilder().dns(dns).build())
        try {
            val done = CountDownLatch(1)
            var status: ModuleResultStatus? = null
            var elapsed = 0L
            request(module, "http://dual-stack.test:${server.address.port}/") { s, ms -> status = s; elapsed = ms; done.countDown() }
            assertTrue("request finished", done.await(15, TimeUnit.SECONDS))
            assertEquals(ModuleResultStatus.SUCCESS, status)
            assertTrue("fell back after ${elapsed}ms", elapsed < 3_000)
        } finally {
            module.close()
            server.stop(0)
        }
    }

    @Test
    fun slowRequestsToOneHostDoNotStarveAnother() {
        val server = server(mapOf("slow.test" to 3_000L))
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val module = HttpModule(baseClient = PamHttpClient.shared.newBuilder().dns(dns).build())
        try {
            val port = server.address.port
            val slow = CountDownLatch(12)
            repeat(12) { request(module, "http://slow.test:$port/") { _, _ -> slow.countDown() } }
            val fast = CountDownLatch(1)
            var elapsed = 0L
            request(module, "http://fast.test:$port/") { _, ms -> elapsed = ms; fast.countDown() }
            assertTrue("fast host answered", fast.await(10, TimeUnit.SECONDS))
            assertTrue("fast host waited ${elapsed}ms behind the slow one", elapsed < 1_500)
            assertTrue(slow.await(20, TimeUnit.SECONDS))
        } finally {
            module.close()
            server.stop(0)
        }
    }

    @Test
    fun cancelAbortsTheNativeRequestByItsKey() {
        val server = server(mapOf("slow.test" to 5_000L))
        val dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }
        val module = HttpModule(baseClient = PamHttpClient.shared.newBuilder().dns(dns).build())
        try {
            val done = CountDownLatch(1)
            var status: ModuleResultStatus? = null
            val started = System.nanoTime()
            module.invoke(
                "request",
                WireMap.encode(
                    mapOf(
                        "url" to WireValue.Text("http://slow.test:${server.address.port}/"),
                        "method" to WireValue.Text("GET"),
                        "headers" to WireValue.Text("{}"),
                        "cancelKey" to WireValue.Integer(7),
                    ),
                ),
            ) { s, _ -> status = s; done.countDown() }
            Thread.sleep(200)
            module.invoke("cancel", WireMap.encode(mapOf("cancelKey" to WireValue.Integer(7)))) { _, _ -> }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(ModuleResultStatus.FAILURE, status)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        } finally {
            module.close()
            server.stop(0)
        }
    }

    @Test
    fun closingTheModuleMidUploadFailsTheCallWithoutCrashingADispatcherThread() {
        // The module closes (hot reload, activity gone) once the connection is
        // up and the file body is about to stream. Anything but an IOException
        // thrown from writeTo is rethrown by OkHttp on its dispatcher thread
        // and kills the app.
        val server = server()
        val directory = java.nio.file.Files.createTempDirectory("pam-upload").toFile()
        val root = File(directory, "files").apply { mkdirs() }
        File(root, "big.bin").writeBytes(ByteArray(256 * 1024))
        val crashes = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> crashes += error }
        lateinit var module: HttpModule
        val closing = PamHttpClient.shared.newBuilder()
            .addNetworkInterceptor { chain -> module.close(); chain.proceed(chain.request()) }
            .build()
        module = HttpModule(root, File(directory, "cache"), closing)
        try {
            val done = CountDownLatch(1)
            var status: ModuleResultStatus? = null
            module.invoke(
                "upload",
                WireMap.encode(
                    mapOf(
                        "url" to WireValue.Text("http://127.0.0.1:${server.address.port}/put"),
                        "headers" to WireValue.Text("{}"),
                        "path" to WireValue.Text("big.bin"),
                    ),
                ),
            ) { s, _ -> status = s; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(ModuleResultStatus.FAILURE, status)
            Thread.sleep(300)
            assertEquals(emptyList<String>(), crashes.map { it.toString() })
        } finally {
            module.close()
            Thread.setDefaultUncaughtExceptionHandler(previous)
            server.stop(0)
            directory.deleteRecursively()
        }
    }
}
