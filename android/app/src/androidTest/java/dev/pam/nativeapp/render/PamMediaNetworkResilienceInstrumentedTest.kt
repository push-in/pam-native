package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Images and videos against a local HTTP server that rate limits (429 with
 * Retry-After), fails, stalls and redirects like the media API of a feed.
 */
@RunWith(AndroidJUnit4::class)
class PamMediaNetworkResilienceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: PamTestActivity
    private lateinit var loader: NativeImageLoader
    private lateinit var server: TestServer
    private val mediaCaches = mutableListOf<NativeMediaFileCache>()
    private val png: ByteArray by lazy {
        ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
    }

    @Before
    fun setUp() {
        PamMediaHostGate.reset()
        File(context.cacheDir, "pam-images-v1").deleteRecursively()
        activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        loader = NativeImageLoader(activity)
    }

    @After
    fun tearDown() {
        main {
            loader.close()
            activity.host.removeAllViews()
            activity.finish()
        }
        mediaCaches.forEach(NativeMediaFileCache::close)
        if (::server.isInitialized) server.close()
        PamMediaHostGate.reset()
    }

    @Test
    fun rateLimitedImageAppearsAfterRetryAfterWithoutFailing() {
        server = TestServer { request ->
            if (request.hit == 1) Response(429, mapOf("Retry-After" to "1")) else image()
        }
        val probe = loadImage("/limited.png")
        assertTrue("Image loads after Retry-After", probe.loaded.await(15, TimeUnit.SECONDS))
        assertEquals("No error is reported for a rate limit", 0, probe.errors.get())
        val hits = server.requests.filter { it.path == "/limited.png" }
        assertEquals(2, hits.size)
        val waited = hits[1].at - hits[0].at
        assertTrue("Retry waits for Retry-After (waited $waited ms)", waited >= 900)
        main { assertTrue(probe.view.drawable != null) }
    }

    @Test
    fun aRateLimitedHostHoldsTheOtherImagesOfThatHost() {
        val limited = CountDownLatch(1)
        server = TestServer { request ->
            if (request.path == "/first.png" && request.hit == 1) {
                limited.countDown()
                Response(429, mapOf("Retry-After" to "2"))
            } else {
                image()
            }
        }
        val first = loadImage("/first.png")
        assertTrue(limited.await(10, TimeUnit.SECONDS))
        val limitedAt = server.requests.first { it.path == "/first.png" }.at
        SystemClock.sleep(100)
        val second = loadImage("/second.png")
        assertTrue(first.loaded.await(15, TimeUnit.SECONDS))
        assertTrue(second.loaded.await(15, TimeUnit.SECONDS))
        val secondAt = server.requests.first { it.path == "/second.png" }.at
        assertTrue(
            "The other image waits for the host pause (${secondAt - limitedAt} ms)",
            secondAt - limitedAt >= 1_800,
        )
        assertEquals("It is requested once, not rate limited too", 1, server.hits("/second.png"))
        assertEquals(0, first.errors.get() + second.errors.get())
    }

    @Test
    fun notFoundFailsAtOnceWithoutRetries() {
        server = TestServer { Response(404) }
        val probe = loadImage("/missing.png")
        assertTrue("404 is reported", probe.failed.await(5, TimeUnit.SECONDS))
        SystemClock.sleep(1_500)
        assertEquals("404 is never retried", 1, server.hits("/missing.png"))
        assertEquals(1, probe.errors.get())
    }

    @Test
    fun stalledResponseTimesOutAndRetries() {
        server = TestServer { request ->
            if (request.hit == 1) Response(200, holdMs = 20_000) else image()
        }
        val started = SystemClock.uptimeMillis()
        val probe = loadImage("/stalled.png")
        assertTrue("A stalled response is retried", probe.loaded.await(20, TimeUnit.SECONDS))
        val elapsed = SystemClock.uptimeMillis() - started
        assertEquals(0, probe.errors.get())
        assertEquals(2, server.hits("/stalled.png"))
        assertTrue("The first attempt gives up before 15 s ($elapsed ms)", elapsed < imageReadTimeoutMs(0) + 3_000)
    }

    @Test
    fun visibleImagesGoBeforeHiddenOnesAndRecycledOnesAreNeverRequested() {
        val gate = Semaphore(0)
        server = TestServer { request ->
            if (request.path.startsWith("/block")) gate.tryAcquire(30, TimeUnit.SECONDS)
            image()
        }
        val blockers = (0 until IMAGE_NETWORK_THREADS).map { loadImage("/block$it.png") }
        awaitHits(IMAGE_NETWORK_THREADS) { it.path.startsWith("/block") }
        lateinit var hiddenParent: FrameLayout
        main {
            hiddenParent = FrameLayout(activity).apply { visibility = View.INVISIBLE }
            activity.host.addView(hiddenParent, FrameLayout.LayoutParams(60, 60))
        }
        val hidden = loadImage("/hidden.png", parent = hiddenParent)
        val recycled = loadImage("/recycled.png")
        val visible = loadImage("/visible.png")
        SystemClock.sleep(200)
        main { loader.cancel(recycled.view) }
        // The cancel reaches the queue on the next demand refresh.
        SystemClock.sleep(400)
        gate.release(1)
        awaitHits(IMAGE_NETWORK_THREADS + 1) { true }
        assertEquals(
            "The first free thread takes the visible image",
            "/visible.png",
            server.requests[IMAGE_NETWORK_THREADS].path,
        )
        gate.release(IMAGE_NETWORK_THREADS)
        assertTrue(visible.loaded.await(10, TimeUnit.SECONDS))
        assertTrue(hidden.loaded.await(10, TimeUnit.SECONDS))
        blockers.forEach { assertTrue(it.loaded.await(10, TimeUnit.SECONDS)) }
        SystemClock.sleep(300)
        assertEquals("A recycled cell's queued image is dropped", 0, server.hits("/recycled.png"))
    }

    @Test
    fun redirectedImageIsCachedUnderItsOwnUrl() {
        server = TestServer { request ->
            when (request.path) {
                "/api/media/photo" -> Response(302, mapOf("Location" to "/cdn/signed-photo.png?sig=${request.hit}"))
                else -> image()
            }
        }
        val first = loadImage("/api/media/photo")
        assertTrue(first.loaded.await(10, TimeUnit.SECONDS))
        assertEquals(1, server.hits("/api/media/photo"))
        main { activity.host.removeAllViews() }
        val fresh = NativeImageLoader(activity)
        try {
            val second = loadImage("/api/media/photo", using = fresh)
            assertTrue(second.loaded.await(10, TimeUnit.SECONDS))
        } finally {
            main { fresh.close() }
        }
        assertEquals("The API is not asked again", 1, server.hits("/api/media/photo"))
        assertEquals("The CDN is not asked again", 1, server.requests.count { it.path.startsWith("/cdn/") })
    }

    @Test
    fun failedImageLoadsAgainWhenShownAgain() {
        val broken = AtomicBoolean(true)
        server = TestServer { if (broken.get()) Response(404) else image() }
        lateinit var parent: FrameLayout
        main {
            parent = FrameLayout(activity)
            activity.host.addView(parent, FrameLayout.LayoutParams(120, 120))
        }
        val probe = loadImage("/later.png", parent = parent)
        assertTrue(probe.failed.await(5, TimeUnit.SECONDS))
        broken.set(false)
        main {
            parent.visibility = View.GONE
            parent.visibility = View.VISIBLE
        }
        assertTrue("A failure does not stick", probe.loaded.await(10, TimeUnit.SECONDS))
        assertEquals(2, server.hits("/later.png"))
    }

    @Test
    fun rateLimitedVideoRecoversWithoutAnError() {
        val clip = videoBytes()
        server = TestServer { request ->
            if (request.hit == 1) Response(429, mapOf("Retry-After" to "1")) else video(request, clip)
        }
        val ready = CountDownLatch(1)
        val errors = AtomicInteger()
        withVideo { media ->
            media.onReady = { ready.countDown() }
            media.onError = { errors.incrementAndGet() }
            media.setSource(server.url("/clip.mp4"))
        }
        assertTrue("The player retries after Retry-After", ready.await(20, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertTrue(server.hits("/clip.mp4") >= 2)
    }

    @Test
    fun failedVideoPreparesAgainInsteadOfStayingBroken() {
        val clip = videoBytes()
        val failing = AtomicBoolean(true)
        server = TestServer { request ->
            if (failing.get()) Response(503, mapOf("Retry-After" to "0")) else video(request, clip)
        }
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        withVideo { media ->
            media.onReady = { ready.countDown() }
            media.onError = {
                failing.set(false)
                failed.countDown()
            }
            media.setSource(server.url("/overloaded.mp4"))
        }
        assertTrue("The player gives up after its retries", failed.await(60, TimeUnit.SECONDS))
        assertTrue("It prepares again and plays", ready.await(20, TimeUnit.SECONDS))
    }

    private class Probe(val view: PamImageView) {
        val loaded = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val errors = AtomicInteger()
    }

    private fun loadImage(
        path: String,
        parent: FrameLayout? = null,
        using: NativeImageLoader = loader,
    ): Probe {
        lateinit var probe: Probe
        main {
            val view = PamImageView(activity)
            (parent ?: activity.host).addView(view, FrameLayout.LayoutParams(48, 48))
            probe = Probe(view)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, 48, 48)
            using.load(
                NativeImageRequest(source = server.url(path), fadeDurationMs = 0),
                view,
                NativeImageCallbacks(
                    onSuccess = { probe.loaded.countDown() },
                    onError = {
                        probe.errors.incrementAndGet()
                        probe.failed.countDown()
                    },
                ),
            )
        }
        return probe
    }

    private fun withVideo(start: (PamMediaView) -> Unit) {
        val cache = NativeMediaFileCache(activity)
        mediaCaches += cache
        lateinit var media: PamMediaView
        main {
            media = PamMediaView(activity, cache, loader)
            media.setMuted(true)
            media.setForwardBufferSeconds(4)
            activity.host.addView(media, FrameLayout.LayoutParams(200, 120))
        }
        instrumentation.waitForIdleSync()
        main { start(media) }
    }

    private fun awaitHits(count: Int, filter: (TestServer.Request) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (server.requests.count(filter) < count && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Expected $count requests, saw ${server.requests.map { it.path }}", server.requests.count(filter) >= count)
    }

    private fun image() = Response(200, mapOf("Content-Type" to "image/png"), png)

    private fun videoBytes(): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("pam-video/red-blue.mp4").use(InputStream::readBytes)

    private fun video(request: TestServer.Request, clip: ByteArray): Response {
        val range = request.headers["range"]?.removePrefix("bytes=")?.split('-')
            ?: return Response(200, mapOf("Content-Type" to "video/mp4", "Accept-Ranges" to "bytes"), clip)
        val first = range[0].toInt()
        val last = range.getOrNull(1)?.takeIf(String::isNotEmpty)?.toInt()?.coerceAtMost(clip.size - 1) ?: (clip.size - 1)
        return Response(
            206,
            mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes $first-$last/${clip.size}"),
            clip.copyOfRange(first, last + 1),
        )
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    class Response(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val holdMs: Long = 0,
    )

    /** One request per connection; records every request with its arrival time. */
    class TestServer(private val handler: (Request) -> Response) : AutoCloseable {
        class Request(val path: String, val headers: Map<String, String>, val hit: Int, val at: Long)

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        private val counts = ConcurrentHashMap<String, AtomicInteger>()
        val requests = CopyOnWriteArrayList<Request>()

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { serve(client) }
                }
            }.apply { isDaemon = true }.start()
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        fun hits(path: String) = requests.count { it.path == path }

        private fun serve(client: Socket) = runCatching {
            client.use {
                val input = client.getInputStream()
                val line = readLine(input) ?: return@use
                val headers = HashMap<String, String>()
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    val colon = header.indexOf(':')
                    if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
                }
                val target = line.split(' ')[1]
                val path = target.substringBefore('?')
                val request = Request(path, headers, counts.getOrPut(path) { AtomicInteger() }.incrementAndGet(), SystemClock.uptimeMillis())
                requests += request
                val response = handler(request)
                if (response.holdMs > 0) Thread.sleep(response.holdMs)
                val out = client.getOutputStream()
                val head = buildString {
                    append("HTTP/1.1 ${response.status} Test\r\n")
                    response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Content-Length: ${response.body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                out.write(response.body)
                out.flush()
            }
        }

        private fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
                if (value == '\n'.code) return bytes.toString("ISO-8859-1").trimEnd('\r')
                bytes.write(value)
            }
        }

        override fun close() {
            runCatching { socket.close() }
            pool.shutdownNow()
        }
    }
}
