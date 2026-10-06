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
                val sha = hexDigest(java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
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
    fun installsPackedBundleAndVerifiesEveryFile() {
        val assets = Files.createTempDirectory("pam-pack-assets")
        val destination = Files.createTempDirectory("pam-pack-destination")
        try {
            val packed = linkedMapOf(
                "index.php" to "<?php echo 1;",
                "pam-prebuilt/components/abc.json" to "{}",
                "src/Components/Row.pam" to "<template><View /></template>",
                "src/Empty.php" to "",
            )
            val plain = mapOf("assets/logo.svg" to "<svg/>", "manifest.sha256" to "${"a".repeat(64)}\n")
            plain.forEach { (path, text) ->
                val file = assets.resolve(path)
                Files.createDirectories(file.parent)
                file.writeText(text)
            }
            fun sha(text: String) = hexDigest(
                java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()),
            )
            fun deflate(data: ByteArray): ByteArray {
                val deflater = java.util.zip.Deflater(9, true)
                deflater.setInput(data)
                deflater.finish()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
                deflater.end()
                return output.toByteArray()
            }
            // Two chunks: the first two files, then the rest.
            fun pack(entries: Map<String, String>, data: String = entries.values.joinToString(""), trailing: ByteArray = ByteArray(0)): ByteArray {
                val groups = entries.entries.toList().let { listOf(it.take(2), it.drop(2)) }
                var cursor = 0
                val chunks = groups.map { group ->
                    val length = group.sumOf { it.value.toByteArray().size }
                    val raw = data.toByteArray().copyOfRange(cursor, cursor + length).also { cursor += length }
                    group to deflate(raw).let { it to raw.size }
                }
                val index = buildString {
                    plain.forEach { (path, text) -> append("${sha(text)} ${text.toByteArray().size} a $path\n") }
                    chunks.forEach { (group, chunk) ->
                        append("c ${chunk.first.size} ${chunk.second}\n")
                        group.forEach { (path, text) -> append("${sha(text)} ${text.toByteArray().size} p $path\n") }
                    }
                }.toByteArray()
                return java.io.ByteArrayOutputStream().apply {
                    write("PNB1".toByteArray())
                    write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(index.size).array())
                    write(index)
                    chunks.forEach { write(it.second.first) }
                    write(trailing)
                }.toByteArray()
            }
            val open = { path: String -> assets.resolve(path).toFile().inputStream() }
            installPackedBundle(pack(packed).inputStream(), destination.toFile(), 3, open)
            (packed + plain).forEach { (path, text) ->
                assertEquals(text, destination.resolve(path).toFile().readText())
            }

            val tampered = pack(packed, packed.values.joinToString("").replace("echo 1", "echo 2"))
            assertTrue(
                "Packed contents that differ from the index must fail",
                runCatching { installPackedBundle(tampered.inputStream(), destination.toFile(), 2, open) }.isFailure,
            )
            val truncated = pack(packed).let { it.copyOf(it.size - 3) }
            assertTrue(
                "A truncated pack must fail",
                runCatching { installPackedBundle(truncated.inputStream(), destination.toFile(), 2, open) }.isFailure,
            )
            val trailing = pack(packed, trailing = byteArrayOf(1))
            assertTrue(
                "Trailing bytes must fail",
                runCatching { installPackedBundle(trailing.inputStream(), destination.toFile(), 2, open) }.isFailure,
            )
            assets.resolve("assets/logo.svg").writeText("<svg>changed</svg>")
            assertTrue(
                "A plain asset that differs from the index must fail",
                runCatching { installPackedBundle(pack(packed).inputStream(), destination.toFile(), 2, open) }.isFailure,
            )
            val sha = "a".repeat(64)
            listOf(
                "x 1 a a",
                "$sha -1 a a",
                "$sha 1 x a",
                "${"A".repeat(64)} 1 a a",
                "$sha 1 a ../a",
                "$sha 1 a a//b",
                "$sha 1 a a\n$sha 1 a a",
                "$sha 1 p a",
                "c 5 2\n$sha 1 p a",
                "c 0 1\n$sha 1 p a",
                "c 5\n$sha 1 p a",
            ).forEach { index ->
                assertTrue("Invalid index must fail: $index", runCatching { parsePackedBundleIndex(index) }.isFailure)
            }
            val parsed = parsePackedBundleIndex("$sha 3 a x.css\nc 5 3\n$sha 1 p a\n$sha 2 p b/c.php\n")
            assertEquals(listOf("x.css"), parsed.plain.map { it.path })
            assertEquals(listOf(listOf("a", "b/c.php")), parsed.chunks.map { chunk -> chunk.files.map { it.path } })
        } finally {
            assets.toFile().deleteRecursively()
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
