package dev.pam.nativeapp.modules

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.WireMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The same registry replacement used by PamRuntime.attach after Activity recreation. */
@RunWith(AndroidJUnit4::class)
class RetainedEventModulesInstrumentedTest {
    @Test
    fun inFlightHttpCompletesAfterRegistryReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val accepted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val server = java.net.ServerSocket(0)
        var status: ModuleResultStatus? = null
        var response = ""
        val worker = Thread {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                accepted.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            }
        }.apply { isDaemon = true; start() }
        lateinit var old: NativeModuleRegistry
        var replacement: NativeModuleRegistry? = null
        try {
            instrumentation.runOnMainSync {
                old = NativeModuleRegistry(instrumentation.targetContext)
                old.invoke("http", "get", WireMap.encode(mapOf(
                    "url" to dev.pam.nativeapp.protocol.WireValue.Text("http://127.0.0.1:${server.localPort}/retained"),
                    "timeoutMs" to dev.pam.nativeapp.protocol.WireValue.Integer(5000),
                )), ModuleCompletion { result, payload ->
                    status = result
                    if (result == ModuleResultStatus.SUCCESS) response = (WireMap.decode(payload)["body"] as? dev.pam.nativeapp.protocol.WireValue.Text)?.value.orEmpty()
                    finished.countDown()
                })
            }
            assertTrue("HTTP request never reached local fixture", accepted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { replacement = NativeModuleRegistry(instrumentation.targetContext, old); old.retire() }
            release.countDown()
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(ModuleResultStatus.SUCCESS, status)
            assertEquals("ok", response)
        } finally {
            release.countDown()
            server.close()
            worker.join(1000)
            instrumentation.runOnMainSync { (replacement ?: old).close() }
        }
    }

    @Test
    fun queuedDurableOperationsSurviveRegistryReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var old: NativeModuleRegistry
        lateinit var replacement: NativeModuleRegistry
        val entered = java.util.concurrent.CountDownLatch(3)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(3)
        val statuses = java.util.Collections.synchronizedList(mutableListOf<ModuleResultStatus>())
        instrumentation.runOnMainSync { old = NativeModuleRegistry(instrumentation.targetContext) }
        for (name in listOf("storage", "sqlite", "cache")) {
            val module = NativeModuleRegistry::class.java.getDeclaredField(name).apply { isAccessible = true }.get(old)
            val executor = module.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(module) as java.util.concurrent.ExecutorService
            executor.execute { entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        }
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try {
            instrumentation.runOnMainSync {
                val done = ModuleCompletion { status, _ -> statuses += status; finished.countDown() }
                old.invoke("storage", "set", WireMap.encode(mapOf(
                    "key" to dev.pam.nativeapp.protocol.WireValue.Text("retained-registry-test"),
                    "value" to dev.pam.nativeapp.protocol.WireValue.Text("completed"),
                )), done)
                old.invoke("sqlite", "query", WireMap.encode(mapOf(
                    "database" to dev.pam.nativeapp.protocol.WireValue.Text("retained-registry-test"),
                    "sql" to dev.pam.nativeapp.protocol.WireValue.Text("SELECT 42 AS answer"),
                    "arguments" to dev.pam.nativeapp.protocol.WireValue.Text("[]"),
                )), done)
                old.invoke("cache", "usage", ByteArray(0), done)
                replacement = NativeModuleRegistry(instrumentation.targetContext, old)
                old.retire()
            }
            release.countDown()
            assertTrue("Queued storage/database calls were lost", finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf(ModuleResultStatus.SUCCESS, ModuleResultStatus.SUCCESS, ModuleResultStatus.SUCCESS), statuses.toList())
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { replacement.close() }
        }
    }

    @Test
    fun pendingTimerSurvivesRebindAndCanBeCancelledFromReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val delivered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        lateinit var replacement: NativeModuleRegistry
        instrumentation.runOnMainSync {
            val old = NativeModuleRegistry(instrumentation.targetContext)
            old.invoke("timers", "after", WireMap.encode(mapOf(
                "timer" to dev.pam.nativeapp.protocol.WireValue.Integer(101),
                "milliseconds" to dev.pam.nativeapp.protocol.WireValue.Integer(50),
            )), ModuleCompletion { status, _ -> if (status == ModuleResultStatus.SUCCESS) delivered.countDown() })
            old.invoke("timers", "after", WireMap.encode(mapOf(
                "timer" to dev.pam.nativeapp.protocol.WireValue.Integer(102),
                "milliseconds" to dev.pam.nativeapp.protocol.WireValue.Integer(5000),
            )), ModuleCompletion { status, _ -> if (status == ModuleResultStatus.FAILURE) cancelled.countDown() })
            replacement = NativeModuleRegistry(instrumentation.targetContext, old)
            old.retire()
            replacement.invoke("timers", "cancel", WireMap.encode(mapOf(
                "timer" to dev.pam.nativeapp.protocol.WireValue.Integer(102),
            )), ModuleCompletion { _, _ -> })
        }
        try {
            assertTrue("Pending runtime timer must complete after surface replacement", delivered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Replacement must retain cancellation identity", cancelled.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { replacement.close() }
        }
    }

    @Test
    fun registryRebindPreservesProcessEventsAndFinalCloseStillReleasesThem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        val results = mutableListOf<Pair<String, ModuleResultStatus>>()
        instrumentation.runOnMainSync {
            val old = NativeModuleRegistry(activity)
            old.prepareReload()
            fun listen(registry: NativeModuleRegistry, module: String, method: String) {
                registry.invoke(module, method, ByteArray(0), ModuleCompletion { status, _ ->
                    results += module to status
                })
            }
            listen(old, "linking", "nextUrl")
            listen(old, "incoming-share", "next")
            listen(old, "notifications", "nextPushEvent")
            listen(old, "notifications", "nextAction")
            val replacement = NativeModuleRegistry(activity, old)
            try {
                old.retire()
                assertTrue("Activity replacement must not fail retained PHP listeners: $results", results.isEmpty())
                PamDeepLinks.reportOpened("https://example.test/retained")
                PamIncomingShares.reportOpened(activity, Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "retained event")
                })
                PamPushNotifications.reportReceived("retained-test", dataJson = "{}")
                assertEquals(listOf("linking", "incoming-share", "notifications"), results.map { it.first })
                assertTrue(results.all { it.second == ModuleResultStatus.SUCCESS })
                // Delivery must permit the normal PHP re-arm through the new registry.
                listen(replacement, "linking", "nextUrl")
                PamDeepLinks.reportOpened("https://example.test/rearmed")
                assertEquals(4, results.size)
                assertEquals(ModuleResultStatus.SUCCESS, results.last().second)
                // The old notification-action waiter still belongs to the live runtime.
                replacement.close()
                assertEquals("notifications" to ModuleResultStatus.FAILURE, results.last())
                assertEquals(5, results.size)
            } finally {
                replacement.prepareReload()
                replacement.close()
                activity.finish()
            }
        }
    }
}
