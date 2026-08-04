package dev.pam.nativeapp

import dev.pam.nativeapp.modules.normalizedBundledAssetPath
import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetInstallerTest {
    @Test
    fun validatesBundledAssetPaths() {
        assertEquals("assets/stories/background.webp", normalizedBundledAssetPath(" assets/stories/background.webp "))
        listOf("", "/absolute.png", "../secret", "assets/../secret", "assets//image.png").forEach { path ->
            val result = runCatching { normalizedBundledAssetPath(path) }
            assertTrue("Expected unsafe path to fail: $path", result.isFailure)
        }
    }

    @Test
    fun keepsActiveAndNewestRollbackRelease() {
        val root = Files.createTempDirectory("pam-releases-test")
        try {
            val active = root.resolve("a".repeat(64)).createDirectory()
            val newestRollback = root.resolve("b".repeat(64)).createDirectory()
            val stale = root.resolve("c".repeat(64)).createDirectory()
            root.resolve("not-a-release").createDirectory()
            root.resolve("d".repeat(64)).createFile().writeText("not a directory")
            newestRollback.setLastModifiedTime(FileTime.fromMillis(3_000))
            stale.setLastModifiedTime(FileTime.fromMillis(2_000))
            active.setLastModifiedTime(FileTime.fromMillis(1_000))

            val deletions = staleReleaseDirectories(
                releasesDirectory = root.toFile(),
                activeRelease = active.toFile(),
                retainedInactiveReleases = 1,
            )

            assertEquals(listOf(stale.toFile()), deletions)
            assertTrue(active.toFile().isDirectory)
            assertTrue(newestRollback.toFile().isDirectory)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
