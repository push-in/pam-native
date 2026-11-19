package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.render.PamRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * App::reload() (`fresh`) relaunches PHP like a cold start: the old request
 * must not record hot reload state, so nothing set before the reload comes
 * back. The development hot reload (default) still records and restores it.
 * Run alone: embedded PHP is process-scoped.
 */
@RunWith(AndroidJUnit4::class)
class PamAppReloadStateInstrumentedTest {
    @Test
    fun appReloadStartsCleanWhileHotReloadKeepsState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-app-reload-state-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val entry = File(directory, "entry.php").apply { writeText(ENTRY.replace('§', '$')) }
        val messages = LinkedBlockingQueue<String>()
        lateinit var runtime: PamRuntime
        instrumentation.runOnMainSync {
            runtime = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, { message ->
                if (message.startsWith("STATE ")) messages.put(message.removePrefix("STATE "))
            })
            runtime.start(entry, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        fun next(): String = messages.poll(30, TimeUnit.SECONDS) ?: error("PHP never reported")
        try {
            assertEquals("cold start", "boot restored=none", next())

            // App::reload(): the label set before the reload must not come back.
            instrumentation.runOnMainSync { runtime.reload(entry.absolutePath, fresh = true) }
            assertEquals("App::reload() restored state", "boot restored=none", next())
            instrumentation.waitForIdleSync()
            assertFalse("App::reload() checkpointed state", messages.any { it.startsWith("checkpoint") })

            // pam dev hot reload: the label survives (Fast Refresh).
            instrumentation.runOnMainSync { runtime.reload(entry.absolutePath) }
            assertEquals("hot reload checkpoint", "checkpoint label=Ontem", next())
            assertEquals("hot reload restore", "boot restored=Ontem", next())
            assertTrue(messages.isEmpty())
        } finally {
            instrumentation.runOnMainSync { runtime.close() }
            directory.deleteRecursively()
        }
    }

    private companion object {
        // A stand-in for the SDK's Runtime: checkpointHotReload() records the
        // request's state where ComponentStatePreserver does, and boot
        // consumes it the same way.
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal {
                final class Runtime {
                    public static function checkpointHotReload(): void {
                        file_put_contents(getenv('PAM_NATIVE_STATE_DIR').'/app-reload-test.txt', §GLOBALS['label']);
                        pam_native_error('STATE checkpoint label='.§GLOBALS['label']);
                    }
                    public static function shutdown(): void {}
                }
            }
            namespace {
                §path = getenv('PAM_NATIVE_STATE_DIR').'/app-reload-test.txt';
                §restored = is_file(§path) ? file_get_contents(§path) : 'none';
                @unlink(§path);
                // State built before the reload (a translated date label).
                §GLOBALS['label'] = 'Ontem';
                pam_native_error('STATE boot restored='.§restored);
            }
        """.trimIndent()
    }
}
