package dev.pam.nativeapp

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DeferredBundleAssetsTest {
    private fun asset(path: String, bytes: ByteArray) = PackedBundleFile(
        path, bytes.size, MessageDigest.getInstance("SHA-256").digest(bytes), false,
    )

    @Test fun streamsAndSkipsUnchangedAssetsByDigest() {
        val root = Files.createTempDirectory("pam-cold-start-assets").toFile()
        try {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            val file = asset("assets/photo.bin", bytes)
            val previous = java.io.File(root, "a".repeat(64)).apply { mkdirs() }
            val current = java.io.File(root, "b".repeat(64)).apply { mkdirs() }
            installPlainBundleFile(previous, file) { ByteArrayInputStream(bytes) }
            installPlainBundleFile(current, file) { error("Unchanged asset must reuse previous digest") }
            installPlainBundleFile(current, file) { error("Already installed digest must skip APK") }
            assertArrayEquals(bytes, java.io.File(current, file.path).readBytes())
            val changed = asset(file.path, "new".toByteArray())
            installPlainBundleFile(current, changed) { ByteArrayInputStream("new".toByteArray()) }
            assertEquals("new", java.io.File(current, file.path).readText())
            previous.deleteRecursively()
            assertTrue(runCatching {
                installPlainBundleFile(current, file) { ByteArrayInputStream("bad".toByteArray()) }
            }.isFailure)
            assertEquals("new", java.io.File(current, file.path).readText())
            assertFalse(java.io.File(current, file.path + ".installing").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun defersOnlyApkReadableAssetsAndKeepsRuntimeMetadata() {
        assertTrue(isDeferredBundleAsset("assets/icons/icon.png"))
        assertFalse(isDeferredBundleAsset("vendor/composer/installed.json"))
        assertFalse(isDeferredBundleAsset("pam-prebuilt/components/a.json"))
        assertFalse(isDeferredBundleAsset("index.php"))
    }

    @Test fun backgroundWorkWaitsForFrameAndStopsWhenHidden() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        DeferredBundleAssets.pause()
        DeferredBundleAssets.enqueue {
            started.countDown()
            try { CountDownLatch(1).await() }
            catch (error: InterruptedException) { interrupted.countDown(); throw error }
        }
        assertFalse(started.await(50, TimeUnit.MILLISECONDS))
        DeferredBundleAssets.resume()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        DeferredBundleAssets.pause()
        assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        // Replace the interrupted task so subsequent tests do not resume it.
        DeferredBundleAssets.enqueue { }
    }
}
