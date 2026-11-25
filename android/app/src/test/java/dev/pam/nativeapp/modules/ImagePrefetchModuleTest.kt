package dev.pam.nativeapp.modules

import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ImagePrefetchModuleTest {
    private fun payload(urls: List<String>, headers: String = "{}", priority: Int = 2) = WireMap.encode(mapOf(
        "urls" to WireValue.Text(JSONArray(urls).toString()),
        "headers" to WireValue.Text(headers),
        "priority" to WireValue.Integer(priority.toLong()),
    ))

    @Test fun higherPriorityDuplicatePromotesQueuedJobWithoutDuplicatingWork() {
        val entered = CountDownLatch(2)
        val releaseA = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val completed = CountDownLatch(4)
        val order = java.util.Collections.synchronizedList(mutableListOf<String>())
        val module = ImagePrefetchModule(null) { object : ImagePrefetchModule.PrefetchLoader {
            override fun prefetch(source: String, headers: String?, cacheKey: String?): Long {
                when (source.substringAfterLast('/')) {
                    "block-a" -> { entered.countDown(); check(releaseA.await(3, TimeUnit.SECONDS)) }
                    "block-b" -> { entered.countDown(); check(releaseB.await(3, TimeUnit.SECONDS)) }
                    else -> order.add(source.substringAfterLast('/'))
                }
                return 1
            }
            override fun close() { releaseA.countDown(); releaseB.countDown() }
        } }
        val done = ModuleCompletion { status, bytes ->
            assertEquals(ModuleResultStatus.SUCCESS, status)
            assertEquals(WireValue.Integer(1), WireMap.decode(bytes)["succeeded"])
            completed.countDown()
        }
        try {
            module.invoke("prefetch", payload(listOf("https://example.test/block-a", "https://example.test/block-b")), ModuleCompletion { _, _ -> })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            module.invoke("prefetch", payload(listOf("https://example.test/shared"), priority = 1), done)
            module.invoke("prefetch", payload(listOf("https://example.test/medium"), priority = 3), done)
            module.invoke("prefetch", payload(listOf("https://example.test/shared"), priority = 5), done)
            module.invoke("prefetch", payload(listOf("https://example.test/shared"), priority = 1), done)
            // Only one worker is released, making priority order deterministic.
            releaseA.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("shared", "medium"), order.toList())
        } finally { releaseA.countDown(); releaseB.countDown(); module.close() }
    }

    @Test fun duplicateInFlightRequestsShareWorkAndBothComplete() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val calls = AtomicInteger()
        val completions = AtomicInteger()
        val module = ImagePrefetchModule(null) { object : ImagePrefetchModule.PrefetchLoader {
            override fun prefetch(source: String, headers: String?, cacheKey: String?): Long {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return 42
            }
            override fun close() { release.countDown() }
        } }
        val completion = ModuleCompletion { status, bytes ->
            assertEquals(ModuleResultStatus.SUCCESS, status)
            assertEquals(WireValue.Integer(1), WireMap.decode(bytes)["succeeded"])
            completions.incrementAndGet()
            completed.countDown()
        }
        try {
            module.invoke("prefetch", payload(listOf("https://example.test/a"), "{\"B\":\"2\",\"A\":\"1\"}"), completion)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            module.invoke("prefetch", payload(listOf("https://example.test/a"), "{\"A\":\"1\",\"B\":\"2\"}"), completion)
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(2, completions.get())
        } finally { module.close() }
    }

    @Test fun rapidResumeCannotExceedTwoWorkersWhileOldCancellationDrains() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val resumed = CountDownLatch(2)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val factories = AtomicInteger()
        val module = ImagePrefetchModule(null) {
            val epoch = factories.incrementAndGet()
            object : ImagePrefetchModule.PrefetchLoader {
                override fun prefetch(source: String, headers: String?, cacheKey: String?): Long {
                    val count = running.incrementAndGet()
                    peak.accumulateAndGet(count) { a, b -> maxOf(a, b) }
                    try {
                        if (epoch == 1) {
                            entered.countDown()
                            // Model a native decoder that cannot interrupt its current frame.
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            while (release.count > 0 && System.nanoTime() < deadline) {
                                try { release.await(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                            }
                            check(release.count == 0L)
                        }
                        return 1
                    } finally { running.decrementAndGet() }
                }
                override fun close() {}
            }
        }
        try {
            module.invoke("prefetch", payload(listOf("https://example.test/old-a", "https://example.test/old-b")), ModuleCompletion { _, _ -> })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            module.setActive(false)
            module.setActive(true)
            for (name in listOf("new-a", "new-b")) {
                module.invoke("prefetch", payload(listOf("https://example.test/$name")), ModuleCompletion { _, bytes ->
                    assertEquals(WireValue.Integer(1), WireMap.decode(bytes)["succeeded"])
                    resumed.countDown()
                })
            }
            assertEquals("No replacement workers are created while two old workers drain", 1, factories.get())
            release.countDown()
            assertTrue(resumed.await(2, TimeUnit.SECONDS))
            assertEquals(2, factories.get())
            assertEquals(2, peak.get())
        } finally { release.countDown(); module.close() }
    }

    @Test fun pendingBudgetOverflowAndBackgroundSettleEveryItemThenResumeUsesFreshLoader() {
        val started = CountDownLatch(2)
        val blocked = CountDownLatch(1)
        val completions = AtomicInteger()
        val failed = AtomicInteger()
        val factories = AtomicInteger()
        val closed = AtomicInteger()
        val module = ImagePrefetchModule(null) {
            val epoch = factories.incrementAndGet()
            object : ImagePrefetchModule.PrefetchLoader {
                override fun prefetch(source: String, headers: String?, cacheKey: String?): Long {
                    if (epoch == 1) {
                        started.countDown()
                        blocked.await(2, TimeUnit.SECONDS)
                    }
                    return 12
                }
                override fun close() { closed.incrementAndGet() }
            }
        }
        val completion = ModuleCompletion { status, bytes ->
            assertEquals(ModuleResultStatus.SUCCESS, status)
            failed.addAndGet((WireMap.decode(bytes)["failed"] as WireValue.Integer).value.toInt())
            completions.incrementAndGet()
        }
        try {
            module.invoke("prefetch", payload((0 until 100).map { "https://example.test/$it" }), completion)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            module.invoke("prefetch", payload((100 until 200).map { "https://example.test/$it" }), completion)
            module.invoke("prefetch", payload(listOf("https://example.test/overflow")), completion)
            assertEquals("Overflow must settle immediately instead of growing the queue", 1, completions.get())
            module.setActive(false)
            assertEquals(3, completions.get())
            assertEquals(201, failed.get())
            assertEquals(1, closed.get())
            module.invoke("prefetch", payload(listOf("https://example.test/hidden")), completion)
            assertEquals(4, completions.get())
            assertEquals(1, factories.get())
            module.setActive(true)
            val resumed = CountDownLatch(1)
            module.invoke("prefetch", payload(listOf("https://example.test/resume")), ModuleCompletion { status, bytes ->
                assertEquals(ModuleResultStatus.SUCCESS, status)
                assertEquals(WireValue.Integer(1), WireMap.decode(bytes)["succeeded"])
                resumed.countDown()
            })
            assertTrue(resumed.await(2, TimeUnit.SECONDS))
            assertEquals(2, factories.get())
            assertEquals("Late canceled workers cannot complete a batch twice", 4, completions.get())
        } finally { blocked.countDown(); module.close() }
    }
}
