package dev.pam.nativeapp.modules

import java.nio.file.Files
import kotlin.io.path.writeBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheModuleTest {
    @Test
    fun usageAndClearPreservePinnedOfflineMedia() {
        val root = Files.createTempDirectory("pam-cache-test")
        try {
            val images = root.resolve("pam-images-v1").also(Files::createDirectories)
            val media = root.resolve("pam-media-v1").also(Files::createDirectories)
            val temporary = root.resolve("pam-incoming-shares").also(Files::createDirectories)
            images.resolve("avatar.image").writeBytes(ByteArray(11))
            media.resolve("ordinary.media").writeBytes(ByteArray(13))
            media.resolve("offline.media").writeBytes(ByteArray(17))
            media.resolve("offline.pin").writeBytes(byteArrayOf(1))
            temporary.resolve("shared.jpg").writeBytes(ByteArray(19))
            val roots = CacheRoots(images.toFile(), media.toFile(), temporary.toFile())

            assertEquals(61, roots.usage().totalBytes)
            assertEquals(5, roots.usage().fileCount)

            roots.clear(preserveOffline = true)

            assertFalse(images.resolve("avatar.image").toFile().exists())
            assertFalse(media.resolve("ordinary.media").toFile().exists())
            assertTrue(media.resolve("offline.media").toFile().exists())
            assertTrue(media.resolve("offline.pin").toFile().exists())
            assertFalse(temporary.resolve("shared.jpg").toFile().exists())
            assertEquals(18, roots.usage().totalBytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun clearCanRemovePinnedOfflineMedia() {
        val root = Files.createTempDirectory("pam-cache-test-all")
        try {
            val images = root.resolve("pam-images-v1").also(Files::createDirectories)
            val media = root.resolve("pam-media-v1").also(Files::createDirectories)
            val temporary = root.resolve("pam-incoming-shares").also(Files::createDirectories)
            media.resolve("offline.media").writeBytes(ByteArray(17))
            media.resolve("offline.pin").writeBytes(byteArrayOf(1))
            val roots = CacheRoots(images.toFile(), media.toFile(), temporary.toFile())

            roots.clear(preserveOffline = false)

            assertEquals(0, roots.usage().totalBytes)
            assertEquals(0, roots.usage().fileCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sandboxCacheFolderIsClearedAndImportsOnlyOnRequest() {
        val root = Files.createTempDirectory("pam-cache-test-files")
        try {
            val images = root.resolve("pam-images-v1").also(Files::createDirectories)
            val media = root.resolve("pam-media-v1").also(Files::createDirectories)
            val temporary = root.resolve("pam-incoming-shares").also(Files::createDirectories)
            val downloads = root.resolve("pam-files/cache/pdf").also(Files::createDirectories)
            val imports = root.resolve("pam-files/imports").also(Files::createDirectories)
            downloads.resolve("terms.pdf").writeBytes(ByteArray(23))
            imports.resolve("picked.jpg").writeBytes(ByteArray(29))
            val roots = CacheRoots(
                images.toFile(),
                media.toFile(),
                temporary.toFile(),
                downloads = root.resolve("pam-files/cache").toFile(),
                imports = imports.toFile(),
            )

            val usage = roots.usage()
            assertEquals(23, usage.temporaryBytes)
            assertEquals(29, usage.importBytes)
            assertEquals(52, usage.totalBytes)
            assertEquals(2, usage.fileCount)

            roots.clear(preserveOffline = true)
            assertFalse(downloads.resolve("terms.pdf").toFile().exists())
            assertTrue(imports.resolve("picked.jpg").toFile().exists())
            assertEquals(29, roots.usage().totalBytes)

            roots.clear(preserveOffline = true, imports = true)
            assertEquals(0, roots.usage().totalBytes)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
