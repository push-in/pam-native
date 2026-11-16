package dev.pam.nativeapp.render

import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMediaNetworkTest {
    @After
    fun resetGate() {
        PamMediaHostGate.clock = { System.nanoTime() / 1_000_000 }
        PamMediaHostGate.reset()
    }

    @Test
    fun `retry after accepts seconds and http dates and is capped`() {
        assertEquals(1_000L, parseRetryAfterMs("1"))
        assertEquals(0L, parseRetryAfterMs("0"))
        assertEquals(MEDIA_RETRY_MAX_MS, parseRetryAfterMs("3600"))
        assertEquals(MEDIA_RETRY_MAX_MS, parseRetryAfterMs("99999999999999999999999"))
        val now = 1_700_000_000_000L
        // Tue, 14 Nov 2023 22:13:25 GMT = now + 5 s
        assertEquals(5_000L, parseRetryAfterMs("Tue, 14 Nov 2023 22:13:25 GMT", now))
        assertEquals(0L, parseRetryAfterMs("Tue, 14 Nov 2023 22:00:00 GMT", now))
        assertNull(parseRetryAfterMs(null))
        assertNull(parseRetryAfterMs(""))
        assertNull(parseRetryAfterMs("soon"))
        assertNull(parseRetryAfterMs("-3"))
    }

    @Test
    fun `backoff doubles with jitter up to thirty seconds`() {
        assertEquals(250L, mediaBackoffMs(0, 0.0))
        assertEquals(500L, mediaBackoffMs(0, 1.0))
        assertEquals(1_000L, mediaBackoffMs(2, 0.0))
        assertEquals(2_000L, mediaBackoffMs(2, 1.0))
        assertEquals(MEDIA_RETRY_MAX_MS, mediaBackoffMs(12, 1.0))
        assertEquals(MEDIA_RETRY_MAX_MS / 2, mediaBackoffMs(40, 0.0))
    }

    @Test
    fun `rate limits and gateway errors retry and honor retry after`() {
        val limited = MediaHttpException(429, 1_000)
        val delay = requireNotNull(mediaRetryDelayMs(CompletionException(limited), 3, 0.5))
        assertTrue("$delay", delay in 1_000..2_000)
        for (status in listOf(408, 502, 503, 504)) {
            assertNotNull(mediaRetryDelayMs(MediaHttpException(status, null), 0, 0.5))
        }
        assertNotNull(mediaRetryDelayMs(SocketTimeoutException("slow"), 0, 0.5))
        assertEquals(2_000L + 0, mediaRetryDelayMs(MediaHostPausedException("api.test", 2_000), 0, 0.0))
    }

    @Test
    fun `client errors and bad bytes are permanent`() {
        for (status in listOf(400, 401, 403, 404, 410, 500)) {
            assertNull("$status", mediaRetryDelayMs(MediaHttpException(status, null), 0, 0.5))
        }
        assertNull(mediaRetryDelayMs(IllegalArgumentException("Image response has an unsupported content type."), 0, 0.5))
        assertNull(mediaRetryDelayMs(IllegalStateException("Image could not be decoded."), 0, 0.5))
        assertNull(mediaRetryDelayMs(FileNotFoundException("gone"), 0, 0.5))
    }

    @Test
    fun `a rate limited host pauses until retry after and then clears`() {
        var now = 10_000L
        PamMediaHostGate.clock = { now }
        assertEquals(0L, PamMediaHostGate.remainingMs("api.test"))
        assertEquals(3_000L, PamMediaHostGate.pause("API.test", 3_000))
        assertEquals(3_000L, PamMediaHostGate.remainingMs("api.test"))
        assertEquals(0L, PamMediaHostGate.remainingMs("cdn.test"))
        // A request that started before the pause does not lift it.
        PamMediaHostGate.succeeded("api.test")
        assertEquals(3_000L, PamMediaHostGate.remainingMs("api.test"))
        now += 3_000
        assertEquals(0L, PamMediaHostGate.remainingMs("api.test"))
        // Without Retry-After the default pause grows per strike.
        val first = PamMediaHostGate.pause("api.test", null)
        now += first
        val second = PamMediaHostGate.pause("api.test", null)
        assertTrue("$first < $second", second > first)
        now += second
        PamMediaHostGate.succeeded("api.test")
        assertEquals(mediaBackoffMs(1, 1.0), PamMediaHostGate.pause("api.test", null))
    }

    @Test
    fun `lane runs the highest priority first and drops unwanted jobs`() {
        val lane = MediaWorkLane("test-lane", 1)
        val gate = CountDownLatch(1)
        val order = mutableListOf<String>()
        val done = CountDownLatch(3)
        val dropped = AtomicInteger()
        fun job(name: String, priority: Int, block: () -> Unit = {}) = object : MediaWorkLane.Job() {
            override fun priority() = priority
            override fun run() {
                block()
                synchronized(order) { order += name }
                done.countDown()
            }
            override fun drop() {
                dropped.incrementAndGet()
                done.countDown()
            }
        }
        lane.submit(job("blocker", MEDIA_PRIORITY_VISIBLE) { gate.await() })
        Thread.sleep(50)
        lane.submit(job("prefetch", MEDIA_PRIORITY_PREFETCH))
        lane.submit(job("gone", MEDIA_PRIORITY_DROP))
        lane.submit(job("visible", MEDIA_PRIORITY_VISIBLE))
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(listOf("blocker", "visible", "prefetch"), synchronized(order) { order.toList() })
        assertEquals(1, dropped.get())
        lane.close()
    }

    @Test
    fun `lane holds a waiting job without blocking others`() {
        val lane = MediaWorkLane("test-lane", 1)
        val order = mutableListOf<String>()
        val done = CountDownLatch(2)
        val release = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
        lane.submit(object : MediaWorkLane.Job() {
            override fun priority() = MEDIA_PRIORITY_VISIBLE
            override fun waitMs() = ((release - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
            override fun run() { synchronized(order) { order += "paused" }; done.countDown() }
            override fun drop() = Unit
        })
        lane.submit(object : MediaWorkLane.Job() {
            override fun priority() = MEDIA_PRIORITY_PREFETCH
            override fun run() { synchronized(order) { order += "other" }; done.countDown() }
            override fun drop() = Unit
        })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("other", "paused"), synchronized(order) { order.toList() })
        lane.close()
    }
}
