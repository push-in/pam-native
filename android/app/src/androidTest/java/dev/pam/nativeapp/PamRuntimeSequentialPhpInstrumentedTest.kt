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

/**
 * Embedded PHP has one lifetime per process at a time. close() stops a
 * runtime on a background thread (php_embed_shutdown runs there), so a
 * runtime started right after it used to run php_embed_init concurrently
 * with the previous shutdown and crash the process (seen when the crypto
 * bridge and reload tests ran back to back). The next PHP lifetime now
 * waits for the previous one to end.
 */
@RunWith(AndroidJUnit4::class)
class PamRuntimeSequentialPhpInstrumentedTest {
    @Test
    fun aRuntimeStartedWhileThePreviousPhpShutsDownWaitsForIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-sequential-php-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val first = File(directory, "first.php").apply { writeText(entry("FIRST", slowShutdown = true)) }
        val second = File(directory, "second.php").apply { writeText(entry("SECOND", slowShutdown = false)) }
        val reports = mutableListOf<String>()
        val firstReady = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        fun runtime(): PamRuntime = PamRuntime(
            context,
            PamRenderer(context, FrameLayout(context)) { _, _, _ -> },
            { message ->
                synchronized(reports) { reports += message }
                if (message.startsWith("FIRST ")) firstReady.countDown()
                if (message.startsWith("SECOND ")) secondReady.countDown()
            },
            installModules = false,
        ).also { it.installModules() }
        lateinit var a: PamRuntime
        lateinit var b: PamRuntime
        instrumentation.runOnMainSync {
            a = runtime()
            a.start(first, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        try {
            assertTrue("first PHP never reported: $reports", firstReady.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                a.close()
                b = runtime()
                b.start(second, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
            }
            assertTrue("second PHP never reported: $reports", secondReady.await(30, TimeUnit.SECONDS))
            val report = synchronized(reports) { reports.first { it.startsWith("SECOND ") } }
            assertEquals("SECOND ${EXPECTED_DIGEST}", report)
        } finally {
            instrumentation.runOnMainSync { runCatching { b.close() } }
            directory.deleteRecursively()
        }
    }

    private fun entry(name: String, slowShutdown: Boolean): String = """
        <?php
        namespace Pam\Native\Internal {
            final class Runtime {
                public static function shutdown(): void {
                    // Keep PHP busy while the next runtime starts.
                    if (__SLOW__) {
                        §until = hrtime(true) + 600_000_000;
                        §x = '';
                        while (hrtime(true) < §until) { §x = sha1(§x.'x'); }
                    }
                }
            }
        }
        namespace {
            §digest = '';
            for (§i = 0; §i < 20000; §i++) { §digest = sha1(§digest.§i); }
            pam_native_error('__NAME__ '.§digest);
        }
    """.trimIndent()
        .replace("__SLOW__", slowShutdown.toString())
        .replace("__NAME__", name)
        .replace('§', '$')

    private companion object {
        /** sha1 chained 20 000 times from '' over the loop index. */
        val EXPECTED_DIGEST: String = run {
            var digest = ""
            val sha = java.security.MessageDigest.getInstance("SHA-1")
            for (i in 0 until 20_000) {
                digest = sha.digest((digest + i).toByteArray()).joinToString("") { "%02x".format(it) }
            }
            digest
        }
    }
}
