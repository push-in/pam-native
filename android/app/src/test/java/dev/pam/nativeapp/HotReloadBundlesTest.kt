package dev.pam.nativeapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HotReloadBundlesTest {
    @Test
    fun keepsLazyAutoloadFilesUntilThePreviousPhpRequestHasShutDown() = withRoot { root ->
        val bundles = HotReloadBundles(root)
        bundles.prepare(BASELINE, bundle("baseline"))
        bundles.previousRequestReleased(BASELINE)
        val lazyClass = File(root, "$BASELINE/Lazy.php")
        lazyClass.writeText("class LoadedDuringShutdown {}")

        bundles.prepare(EDITED, bundle("edited"))

        assertTrue(bundles.hasPendingRequest)
        assertEquals("class LoadedDuringShutdown {}", lazyClass.readText())
        assertEquals(2, root.listFiles()?.size)
        assertThrows(IllegalStateException::class.java) {
            bundles.prepare(THIRD, bundle("third"))
        }
        bundles.previousRequestReleased(THIRD)
        assertTrue(lazyClass.isFile)

        bundles.previousRequestReleased(EDITED)

        assertFalse(lazyClass.exists())
        assertEquals(EDITED, bundles.currentVersion)
        assertFalse(bundles.hasPendingRequest)
        assertEquals(1, root.listFiles()?.size)
    }

    @Test
    fun editRestoreAndFurtherEditsStayBoundedWithoutRemovingTheExecutingRequest() = withRoot { root ->
        val bundles = HotReloadBundles(root)
        var previous: String? = null
        listOf(BASELINE, EDITED, BASELINE, THIRD, EDITED).forEach { version ->
            val entry = bundles.prepare(version, bundle(version))
            assertEquals(version, entry.readText())
            if (previous != null) assertTrue(File(root, "$previous/index.php").isFile)
            assertTrue(root.listFiles().orEmpty().size <= 2)
            bundles.previousRequestReleased(version)
            assertEquals(listOf(version), root.listFiles().orEmpty().map(File::getName))
            previous = version
        }
    }

    @Test
    fun reattachedClientReusesTheImmutableBundleInsteadOfReplacingLiveFiles() = withRoot { root ->
        val entry = File(root, "$BASELINE/index.php")
        checkNotNull(entry.parentFile).mkdirs()
        entry.writeText("retained runtime")
        val bundles = HotReloadBundles(root)

        assertEquals(entry, bundles.prepare(BASELINE, byteArrayOf()))
        assertEquals("retained runtime", entry.readText())
        bundles.previousRequestReleased(BASELINE)
        assertTrue(entry.isFile)
    }

    @Test
    fun rejectedExtractionDoesNotBlockTheNextValidEdit() = withRoot { root ->
        val bundles = HotReloadBundles(root)
        assertThrows(IllegalArgumentException::class.java) {
            bundles.prepare(BASELINE, byteArrayOf())
        }
        assertFalse(bundles.hasPendingRequest)
        assertEquals("valid", bundles.prepare(EDITED, bundle("valid")).readText())
    }

    private fun bundle(content: String): ByteArray = ByteArrayOutputStream().apply {
        write("PNA1".toByteArray())
        write(byteArrayOf(1, 0, 0, 0))
        val path = "index.php".toByteArray()
        write(byteArrayOf(path.size.toByte(), 0))
        write(path)
        val bytes = content.toByteArray()
        repeat(4) { write((bytes.size ushr (it * 8)) and 0xff) }
        write(bytes)
    }.toByteArray()

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pam-reload-lifetime").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    private companion object {
        const val BASELINE = "1111111111111111"
        const val EDITED = "2222222222222222"
        const val THIRD = "3333333333333333"
    }
}
