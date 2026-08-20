package dev.pam.nativeapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DevBundleTest {
    @Test
    fun extractsAndAtomicallyReplacesTheActiveBundle() {
        withDirectory { root ->
            val destination = File(root, "version")
            destination.mkdirs()
            File(destination, "index.php").writeText("old")

            val entry = DevBundle.extract(
                bundle("index.php" to "new", "src/App.php" to "app"),
                destination,
            )

            assertEquals("new", entry.readText())
            assertEquals("app", File(destination, "src/App.php").readText())
            assertFalse(File(root, ".version.incoming").exists())
            assertFalse(File(root, ".version.previous").exists())
        }
    }

    @Test
    fun malformedBundlePreservesTheActiveVersionAndRemovesStaging() {
        withDirectory { root ->
            val destination = File(root, "version")
            destination.mkdirs()
            File(destination, "index.php").writeText("last-known-good")
            val truncated = bundle("index.php" to "replacement").copyOfRange(0, 9)

            assertThrows(IllegalArgumentException::class.java) {
                DevBundle.extract(truncated, destination)
            }

            assertEquals("last-known-good", File(destination, "index.php").readText())
            assertFalse(File(root, ".version.incoming").exists())
        }
    }

    @Test
    fun duplicateAndTraversalPathsFailWithoutReplacingTheActiveVersion() {
        withDirectory { root ->
            val destination = File(root, "version")
            destination.mkdirs()
            File(destination, "index.php").writeText("active")

            assertThrows(IllegalArgumentException::class.java) {
                DevBundle.extract(
                    bundle("index.php" to "first", "index.php" to "second"),
                    destination,
                )
            }
            assertThrows(IllegalArgumentException::class.java) {
                DevBundle.extract(bundle("../index.php" to "escape"), destination)
            }

            assertEquals("active", File(destination, "index.php").readText())
            assertFalse(File(root, ".version.incoming").exists())
        }
    }

    @Test
    fun recoversAnInterruptedBackupBeforeProcessingTheNextBundle() {
        withDirectory { root ->
            val destination = File(root, "version")
            val backup = File(root, ".version.previous")
            backup.mkdirs()
            File(backup, "index.php").writeText("recoverable")

            assertThrows(IllegalArgumentException::class.java) {
                DevBundle.extract(byteArrayOf(), destination)
            }

            assertTrue(destination.isDirectory)
            assertEquals("recoverable", File(destination, "index.php").readText())
            assertFalse(backup.exists())
        }
    }

    private fun bundle(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().apply {
            write("PNA1".toByteArray())
            writeU32(files.size)
            files.forEach { (path, contents) ->
                val encodedPath = path.toByteArray()
                val encodedContents = contents.toByteArray()
                writeU16(encodedPath.size)
                write(encodedPath)
                writeU32(encodedContents.size)
                write(encodedContents)
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        repeat(4) { shift -> write((value ushr (shift * 8)) and 0xff) }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("pam-dev-bundle-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
