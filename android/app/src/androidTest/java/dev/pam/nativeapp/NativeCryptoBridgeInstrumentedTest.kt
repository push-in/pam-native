package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.render.PamRenderer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end through the embedded PHP runtime: a PHP entry calls
 * pam_native_crypto() (pam_android_bridge.cpp -> PamRuntime.onNativeCrypto ->
 * PamCrypto) for every libsodium/OpenSSL vector and reports through
 * pam_native_error(). Proves the runtime has no ext-sodium/ext-openssl and
 * that the C++ marshalling (argument order, bool/string/false results) is
 * what Pam\Native\Crypto expects.
 *
 * Embedded PHP is process scoped: run this class on its own
 * (`-e class dev.pam.nativeapp.NativeCryptoBridgeInstrumentedTest`).
 */
@RunWith(AndroidJUnit4::class)
class NativeCryptoBridgeInstrumentedTest {
    @Test
    fun phpCallsTheNativeCryptoProvider() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "pam-crypto-bridge-test").apply { deleteRecursively(); mkdirs() }
        instrumentation.context.assets.open("crypto-vectors.json").use { input ->
            File(directory, "crypto-vectors.json").outputStream().use { input.copyTo(it) }
        }
        val entry = File(directory, "entry.php").apply { writeText(ENTRY) }
        val reports = mutableListOf<String>()
        val reported = CountDownLatch(1)
        lateinit var runtime: PamRuntime
        instrumentation.runOnMainSync {
            val renderer = PamRenderer(context, FrameLayout(context)) { _, _, _ -> }
            runtime = PamRuntime(
                context = context,
                renderer = renderer,
                reportError = { message ->
                    synchronized(reports) { reports += message }
                    if (message.startsWith("PAMCRYPTO1 ")) reported.countDown()
                },
            )
            runtime.start(entry, widthDp = 360f, heightDp = 640f, textScale = 1f, darkAppearance = false)
        }
        try {
            assertTrue("PHP never reported: $reports", reported.await(60, TimeUnit.SECONDS))
            val report = JSONObject(synchronized(reports) { reports.first { it.startsWith("PAMCRYPTO1 ") } }.removePrefix("PAMCRYPTO1 "))
            assertEquals(report.toString(), 0, report.getJSONArray("failures").length())
            assertTrue(report.toString(), report.getInt("checked") >= 120)
            assertFalse("The Android PHP runtime must not have ext-sodium", report.getBoolean("sodium"))
            assertFalse("The Android PHP runtime must not have ext-openssl", report.getBoolean("openssl"))
            android.util.Log.i("PamNativeCrypto", "PHP bridge: $report")
        } finally {
            instrumentation.runOnMainSync { runtime.close() }
            directory.deleteRecursively()
        }
    }

    private companion object {
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal {
                // The bridge calls Runtime::shutdown() when it stops; nothing else here.
                final class Runtime { public static function shutdown(): void {} }
            }
            namespace {
            ${'$'}vectors = json_decode(file_get_contents(__DIR__.'/crypto-vectors.json'), true);
            ${'$'}failures = [];
            ${'$'}checked = 0;
            ${'$'}started = hrtime(true);
            foreach (${'$'}vectors['ed25519'] as ${'$'}v) {
                ${'$'}checked++;
                ${'$'}result = pam_native_crypto(1, hex2bin(${'$'}v['publicKey']), hex2bin(${'$'}v['signature']), '', hex2bin(${'$'}v['message']));
                if (${'$'}result !== ${'$'}v['valid']) { ${'$'}failures[] = 'ed25519 '.${'$'}v['name'].': '.var_export(${'$'}result, true); }
            }
            ${'$'}ed25519Ms = (hrtime(true) - ${'$'}started) / 1e6;
            foreach (${'$'}vectors['aes256gcm'] as ${'$'}v) {
                ${'$'}checked++;
                [${'$'}key, ${'$'}nonce, ${'$'}aad, ${'$'}plain, ${'$'}sealed] = array_map('hex2bin', [${'$'}v['key'], ${'$'}v['nonce'], ${'$'}v['aad'], ${'$'}v['plaintext'], ${'$'}v['ciphertext']]);
                if (pam_native_crypto(2, ${'$'}key, ${'$'}nonce, ${'$'}aad, ${'$'}plain) !== ${'$'}sealed) { ${'$'}failures[] = 'seal '.${'$'}v['name']; }
                if (pam_native_crypto(3, ${'$'}key, ${'$'}nonce, ${'$'}aad, ${'$'}sealed) !== ${'$'}plain) { ${'$'}failures[] = 'open '.${'$'}v['name']; }
            }
            foreach (${'$'}vectors['aes256gcmOpenFailures'] as ${'$'}v) {
                ${'$'}checked++;
                if (pam_native_crypto(3, hex2bin(${'$'}v['key']), hex2bin(${'$'}v['nonce']), hex2bin(${'$'}v['aad']), hex2bin(${'$'}v['ciphertext'])) !== false) { ${'$'}failures[] = 'opened '.${'$'}v['name']; }
            }
            if (pam_native_crypto(99, '', '', '', '') !== false) { ${'$'}failures[] = 'unknown operation accepted'; }
            pam_native_error('PAMCRYPTO1 '.json_encode([
                'failures' => ${'$'}failures,
                'checked' => ${'$'}checked,
                'sodium' => extension_loaded('sodium'),
                'openssl' => extension_loaded('openssl'),
                'php' => PHP_VERSION,
                'ed25519Ms' => round(${'$'}ed25519Ms, 1),
            ]));
            }
        """.trimIndent()
    }
}
