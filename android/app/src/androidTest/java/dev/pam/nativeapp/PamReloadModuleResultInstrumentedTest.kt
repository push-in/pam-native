package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.modules.NativeModuleRegistry
import dev.pam.nativeapp.modules.PamDeepLinks
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.PamRenderer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Run alone: embedded PHP is process-scoped, just like the native crypto bridge test. */
@RunWith(AndroidJUnit4::class)
class PamReloadModuleResultInstrumentedTest {
    @Test
    fun shutdownCompletionsCannotConsumeNewLinkingRequestIds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-reload-module-result-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val oldEntry = File(directory, "old.php").apply { writeText(entry(true)) }
        val newEntry = File(directory, "new.php").apply { writeText(entry(false)) }
        val ready = CountDownLatch(1)
        val reloaded = CountDownLatch(1)
        val received = CountDownLatch(4)
        val reports = mutableListOf<String>()
        val results = mutableListOf<JSONObject>()
        val held = ConcurrentHashMap<String, ModuleCompletion>()
        val probeModule = object : NativeModule {
            override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
                held[method] = completion
            }
        }
        lateinit var runtime: PamRuntime
        instrumentation.runOnMainSync {
            runtime = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, { message ->
                synchronized(reports) { reports += message }
                when {
                    message == "RELOAD_OLD_READY" -> ready.countDown()
                    message == "RELOAD_NEW_READY" -> reloaded.countDown()
                    message.startsWith("RELOAD_RESULT ") -> {
                        synchronized(results) { results += JSONObject(message.removePrefix("RELOAD_RESULT ")) }
                        received.countDown()
                    }
                }
            })
            val registry = PamRuntime::class.java.getDeclaredField("installedModules")
                .apply { isAccessible = true }.get(runtime) as NativeModuleRegistry
            val modules = NativeModuleRegistry::class.java.getDeclaredField("modules").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val installed = modules.get(registry) as Map<String, NativeModule>
            modules.set(registry, installed + ("reload-probe" to probeModule))
            runtime.start(oldEntry, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        try {
            assertTrue("Initial PHP request never became ready: $reports", ready.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { runtime.reload(newEntry.absolutePath) }
            assertTrue("New PHP request never became ready: $reports", reloaded.await(30, TimeUnit.SECONDS))
            assertTrue("Old and new requests must reach the native module", held.keys.containsAll(listOf("beforeReload", "duringShutdown", "current")))
            instrumentation.runOnMainSync {
                // Both complete after the new PHP entry reused request ID 3.
                // Existing registry generation guards cover beforeReload;
                // the bridge must also cover calls made during shutdown.
                held.getValue("beforeReload").complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                held.getValue("duringShutdown").complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                held.getValue("current").complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(mapOf("fresh" to WireValue.Flag(true))),
                )
                PamDeepLinks.reportOpened("test://reload-proof")
            }
            assertTrue("Linking results never arrived: $reports", received.await(30, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val snapshot = synchronized(results) { results.toList() }
            android.util.Log.i("PamReloadResult", snapshot.toString())
            assertEquals("Only the four current-request callbacks may finish: $snapshot", 4, snapshot.size)
            assertEquals(listOf(1, 2, 3, 4), snapshot.map { it.getInt("requestId") }.sorted())
            assertTrue("New Linking handlers received an unrelated empty cleanup result: $snapshot", snapshot.all {
                it.getInt("status") == 1 && it.getInt("payloadBytes") >= 2
            })
        } finally {
            instrumentation.runOnMainSync { runtime.close() }
            directory.deleteRecursively()
        }
    }

    private fun entry(shutdownCalls: Boolean): String = ENTRY
        .replace("__SHUTDOWN__", shutdownCalls.toString())
        .replace('§', '$')

    private companion object {
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal {
                final class Runtime {
                    public static function shutdown(): void {
                        if (!SHUTDOWN_CALLS) return;
                        // The official Runtime resets nextRequestId to 1 before
                        // ComponentLifecycle::shutdown; cleanup cancels then reuse 1/2.
                        §payload = pack('v', 1).pack('v', 5).'timer'."\x02".pack('P', 41);
                        pam_native_call(1, 'timers', 'cancel', §payload);
                        pam_native_call(2, 'timers', 'cancel', §payload);
                        pam_native_call(3, 'reload-probe', 'duringShutdown', pack('v', 0));
                    }
                    public static function dispatchModuleResult(int §id, int §status, string §payload): void {
                        // Record metadata only: no URLs, image data or session values.
                        pam_native_error('RELOAD_RESULT '.json_encode([
                            'requestId' => §id,
                            'status' => §status,
                            'payloadBytes' => strlen(§payload),
                        ]));
                    }
                }
            }
            namespace {
                define('SHUTDOWN_CALLS', __SHUTDOWN__);
                if (SHUTDOWN_CALLS) {
                    pam_native_call(81, 'linking', 'nextUrl', pack('v', 0));
                    pam_native_call(3, 'reload-probe', 'beforeReload', pack('v', 0));
                    pam_native_error('RELOAD_OLD_READY');
                } else {
                    pam_native_call(1, 'linking', 'initialUrl', pack('v', 0));
                    pam_native_call(2, 'linking', 'nextUrl', pack('v', 0));
                    pam_native_call(3, 'reload-probe', 'current', pack('v', 0));
                    §url = 'https://example.test/reload-proof';
                    §payload = pack('v', 1).pack('v', 3).'url'."\x01".pack('V', strlen(§url)).§url;
                    pam_native_call_typed(4, 8, §payload); // existing CanOpenUrl operation
                    pam_native_error('RELOAD_NEW_READY');
                }
            }
        """.trimIndent()
    }
}
