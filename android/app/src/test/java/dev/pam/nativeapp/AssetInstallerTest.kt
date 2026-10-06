package dev.pam.nativeapp

import dev.pam.nativeapp.modules.normalizedBundledAssetPath
import dev.pam.nativeapp.modules.relativeSandboxPath
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
    fun installsListedBundleInParallelAndVerifiesEveryFile() {
        val source = Files.createTempDirectory("pam-listed-source")
        val destination = Files.createTempDirectory("pam-listed-destination")
        try {
            val contents = mapOf(
                "index.php" to "<?php echo 1;",
                "src/Components/Row.pam.php" to "<?php ?><template><View /></template>",
                "pam-prebuilt/components/abc.json" to "{}",
            )
            val lines = contents.map { (path, text) ->
                val file = source.resolve(path)
                Files.createDirectories(file.parent)
                file.writeText(text)
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                "$sha ${text.toByteArray().size} $path"
            }
            installListedBundle(parseBundleListing(lines), destination.toFile(), 3) { path ->
                source.resolve(path).toFile().inputStream()
            }
            contents.forEach { (path, text) ->
                assertEquals(text, destination.resolve(path).toFile().readText())
            }

            source.resolve("index.php").writeText("<?php echo 2;")
            val tampered = runCatching {
                installListedBundle(parseBundleListing(lines), destination.toFile(), 2) { path ->
                    source.resolve(path).toFile().inputStream()
                }
            }
            assertTrue("A file that differs from its listing must fail", tampered.isFailure)
            listOf("x 1 a", "${"a".repeat(64)} -1 a", "${"a".repeat(64)} 1 ../a", "${"a".repeat(64)} 1 a//b")
                .forEach { line ->
                    assertTrue("Invalid listing must fail: $line", runCatching { parseBundleListing(listOf(line)) }.isFailure)
                }
        } finally {
            source.toFile().deleteRecursively()
            destination.toFile().deleteRecursively()
        }
    }

    @Test
    fun derivesPathsAgainstCanonicalSandboxRoot() {
        val parent = Files.createTempDirectory("pam-file-root-test")
        try {
            val canonicalRoot = parent.resolve("canonical").createDirectory()
            val alias = parent.resolve("alias")
            Files.createSymbolicLink(alias, canonicalRoot)
            val child = canonicalRoot.resolve("story-responses").createDirectory()
                .resolve("response.webp").createFile()

            assertEquals(
                "story-responses/response.webp",
                relativeSandboxPath(alias.toFile(), child.toFile()),
            )
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

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

    @Test
    fun stagesApplicationBundlesInDurableFilesInsteadOfPurgeableCache() {
        val files = Files.createTempDirectory("pam-files-test")
        try {
            val version = "a".repeat(64)
            val staging = installationStagingDirectory(files.toFile(), version)
            assertEquals(
                files.resolve("pam/staging/pam-install-$version").toFile(),
                staging,
            )
            assertTrue(staging.canonicalPath.startsWith(files.toFile().canonicalPath))
        } finally {
            files.toFile().deleteRecursively()
        }
    }
}
