package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.modules.NativeModuleRegistry
import dev.pam.nativeapp.render.PamRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A PHP fatal error, an exception escaping the runtime or `exit()` used to
 * reach zend_bailout() with no bailout address: PHP then called exit(-1),
 * the process died with code 255 and C++ static destructors ran under the
 * live RenderThread ("~EglManager() leaked an EGL context", then FORTIFY
 * "pthread_mutex_lock called on a destroyed mutex"). The worker must report
 * a fatal error instead, keep the process alive and serve the next reload.
 *
 * Run alone: embedded PHP is process-scoped. A regression kills the
 * instrumentation process instead of failing an assertion.
 */
@RunWith(AndroidJUnit4::class)
class PamPhpFatalRecoveryInstrumentedTest {
    @Test
    fun exitInAnEventHandlerIsReportedAndTheRuntimeReloads() = assertRecovers("exit", "exit(7)")

    @Test
    fun memoryExhaustionInAnEventHandlerIsReportedAndTheRuntimeReloads() =
        assertRecovers("memory", "Allowed memory size")

    @Test
    fun exceptionEscapingTheRuntimeIsReportedAndTheRuntimeReloads() =
        assertRecovers("throw", "escaped the runtime")

    @Test
    fun fatalErrorInTheEntryIsReportedAndTheRuntimeReloads() =
        assertRecovers("entry", "Allowed memory size", triggerFromEvent = false)

    @Test
    fun exitInTheEntryIsReportedAndTheRuntimeReloads() =
        assertRecovers("entry-exit", "exit(3)", triggerFromEvent = false)

    @Test
    fun closingAFailedRuntimeLetsTheNextRuntimeBootPhp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-php-fatal-close").apply {
            deleteRecursively()
            mkdirs()
        }
        val failing = File(directory, "failing.php").apply { writeText(entry("entry")) }
        val healthy = File(directory, "healthy.php").apply { writeText(entry("none")) }
        val fatal = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val reports = mutableListOf<String>()
        val record: (String) -> Unit = { message ->
            synchronized(reports) { reports += message }
            if (message.startsWith(REPORT_PREFIX)) fatal.countDown()
            if (message == "READY none") ready.countDown()
        }
        lateinit var first: PamRuntime
        instrumentation.runOnMainSync {
            first = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, record)
            first.start(failing, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        var second: PamRuntime? = null
        try {
            assertTrue("The fatal PHP error was never reported: ${snapshot(reports)}", fatal.await(30, TimeUnit.SECONDS))
            // close() stops the worker while it waits for a reload; PHP must
            // shut down cleanly so the next runtime can initialize it again.
            instrumentation.runOnMainSync {
                first.close()
                second = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, record)
                second!!.start(healthy, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
            }
            assertTrue("The next runtime never booted PHP: ${snapshot(reports)}", ready.await(30, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                first.close()
                second?.close()
            }
            directory.deleteRecursively()
        }
    }

    private fun assertRecovers(mode: String, expected: String, triggerFromEvent: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-php-fatal-$mode").apply {
            deleteRecursively()
            mkdirs()
        }
        val failing = File(directory, "failing.php").apply { writeText(entry(mode)) }
        val healthy = File(directory, "healthy.php").apply { writeText(entry("none")) }
        val ready = CountDownLatch(1)
        val fatal = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val reports = mutableListOf<String>()
        val held = ConcurrentHashMap<String, ModuleCompletion>()
        val probe = object : NativeModule {
            override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
                held[method] = completion
            }
        }
        lateinit var runtime: PamRuntime
        instrumentation.runOnMainSync {
            runtime = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, { message ->
                synchronized(reports) { reports += message }
                when {
                    message == "READY $mode" -> ready.countDown()
                    message == "READY none" -> recovered.countDown()
                    message.startsWith(REPORT_PREFIX) -> fatal.countDown()
                }
            })
            val registry = PamRuntime::class.java.getDeclaredField("installedModules")
                .apply { isAccessible = true }.get(runtime) as NativeModuleRegistry
            val modules = NativeModuleRegistry::class.java.getDeclaredField("modules").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val installed = modules.get(registry) as Map<String, NativeModule>
            modules.set(registry, installed + ("fatal-probe" to probe))
            runtime.start(failing, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        try {
            if (triggerFromEvent) {
                assertTrue("PHP entry never became ready: ${snapshot(reports)}", ready.await(30, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    held.getValue("trigger").complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
            }
            assertTrue("The fatal PHP error was never reported: ${snapshot(reports)}", fatal.await(30, TimeUnit.SECONDS))
            val report = RuntimeErrorReport.parse(snapshot(reports).first { it.startsWith(REPORT_PREFIX) })
            assertTrue("A request-ending PHP error must be fatal: $report", report.fatal)
            assertTrue("Report must name the PHP error ($expected): ${report.message}", report.message.contains(expected))
            assertTrue("The runtime must still be running after a PHP fatal", runtime.isRunning)
            // Release recovery and the debug overlay's Reload both reload the entry.
            instrumentation.runOnMainSync { runtime.reload(healthy.absolutePath) }
            assertTrue("The runtime never served the next request: ${snapshot(reports)}", recovered.await(30, TimeUnit.SECONDS))
            assertEquals(
                "Exactly one fatal report per failed request: ${snapshot(reports)}",
                1,
                snapshot(reports).count { it.startsWith(REPORT_PREFIX) },
            )
            assertFalse(
                "Runtime::shutdown must not run on a request PHP already abandoned",
                snapshot(reports).contains("SHUTDOWN $mode"),
            )
        } finally {
            instrumentation.runOnMainSync { runtime.close() }
            directory.deleteRecursively()
        }
    }

    private fun snapshot(reports: MutableList<String>): List<String> = synchronized(reports) { reports.toList() }

    private fun entry(mode: String): String = ENTRY.replace("__MODE__", mode).replace('§', '$')

    private companion object {
        const val REPORT_PREFIX = "PAMERR1\n"
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal {
                final class Runtime {
                    public static function dispatchModuleResult(int §id, int §status, string §payload): void {
                        fail(MODE);
                    }
                    public static function shutdown(): void {
                        pam_native_error('SHUTDOWN '.MODE);
                    }
                }
            }
            namespace {
                define('MODE', '__MODE__');
                function fail(string §mode): void {
                    if (§mode === 'exit') {
                        exit(7);
                    }
                    if (§mode === 'entry-exit') {
                        exit(3);
                    }
                    if (§mode === 'memory' || §mode === 'entry') {
                        ini_set('memory_limit', '16M');
                        §keep = [];
                        while (true) {
                            §keep[] = str_repeat('x', 1048576);
                        }
                    }
                    if (§mode === 'throw') {
                        throw new \RuntimeException('escaped the runtime');
                    }
                }
                if (MODE === 'entry' || MODE === 'entry-exit') {
                    fail(MODE);
                }
                pam_native_call(1, 'fatal-probe', 'trigger', pack('v', 0));
                pam_native_error('READY '.MODE);
            }
        """.trimIndent()
    }
}
