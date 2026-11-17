package dev.pam.nativeapp.modules

import com.sun.net.httpserver.HttpServer
import dev.pam.nativeapp.net.PamHttpClient
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
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
}
