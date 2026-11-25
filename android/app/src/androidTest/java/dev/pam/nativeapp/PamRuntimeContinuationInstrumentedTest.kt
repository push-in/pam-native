package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.render.PamRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Executes the real embedded PHP worker; no touch or timer wakes the continuation. */
@RunWith(AndroidJUnit4::class)
class PamRuntimeContinuationInstrumentedTest {
    @Test
    fun pendingTurnsContinueWithoutInputAndStopFlushingWhenIdle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-continuation-test").apply { mkdirs() }
        val entry = File(directory, "entry.php").apply { writeText(ENTRY.replace('§', '$')) }
        val finished = CountDownLatch(1)
        val flushes = AtomicInteger()
        lateinit var runtime: PamRuntime
        instrumentation.runOnMainSync {
            runtime = PamRuntime(context, PamRenderer(context, FrameLayout(context)) { _, _, _ -> }, { message ->
                if (message == "CONTINUATION_TURN") flushes.incrementAndGet()
                if (message == "CONTINUATION_DONE") finished.countDown()
            }, installModules = false)
            // This fixture uses no modules. Keep app plugins out of the worker contract.
            runtime.start(entry, 360f, 640f, 1f, false)
        }
        try {
            assertTrue("Pending PHP work required another native event", finished.await(10, TimeUnit.SECONDS))
            // An interruptible worker must return to its indefinite wait once demand ends.
            Thread.sleep(250)
            assertEquals("Idle worker kept calling Runtime::flush", 3, flushes.get())
        } finally {
            instrumentation.runOnMainSync { runtime.close() }
            directory.deleteRecursively()
        }
    }

    private companion object {
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal;
            final class Runtime {
                private static int §remaining = 3;
                public static function deferRendering(bool §deferred): void {}
                public static function hasPendingWork(): bool { return self::§remaining > 0; }
                public static function hasPendingRender(): bool { return false; }
                public static function flush(): bool {
                    \pam_native_error('CONTINUATION_TURN');
                    if (--self::§remaining === 0) \pam_native_error('CONTINUATION_DONE');
                    return false;
                }
                public static function shutdown(): void {}
            }
        """.trimIndent()
    }
}
