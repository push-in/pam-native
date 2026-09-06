package dev.pam.nativeapp.modules

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HttpUploadSnapshotTest {
    @Test fun snapshotIsStableAndRemovedAfterUse() = temporary { root, cache ->
        val source = File(root, "document.bin").apply { writeBytes(byteArrayOf(0, 1, -1, 3)) }
        val snapshot = HttpUploadSnapshot.create(root, cache, source.name)
        source.writeText("changed original")
        assertArrayEquals(byteArrayOf(0, 1, -1, 3), snapshot.file.readBytes())
        val path = snapshot.file
        snapshot.close()
        snapshot.close()
        assertFalse(path.exists())
        assertTrue(source.exists())
    }

    @Test fun refusesTraversalMissingFilesDirectoriesAndEscapingSymlinks() = temporary { root, cache ->
        File(root, "directory").mkdir()
        for (path in listOf("", "../outside", "/absolute", "directory", "missing", "a/../b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) { HttpUploadSnapshot.create(root, cache, path) }
        }
        val outside = File(cache.parentFile, "outside").apply { writeText("private") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { HttpUploadSnapshot.create(root, cache, "link") }
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test fun rejectsOversizeBeforeCopyAndCleansAnInterruptedCopy() = temporary { root, cache ->
        val source = File(root, "large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(HttpUploadSnapshot.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { HttpUploadSnapshot.create(root, cache, source.name) }
        source.writeBytes(ByteArray(128 * 1024))
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            HttpUploadSnapshot.create(root, cache, source.name) { ++checks >= 3 }
        }
        assertTrue(cache.listFiles().isNullOrEmpty())
        assertTrue(source.exists())
    }

    private fun temporary(test: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("pam-http-snapshot-test").toFile()
        try {
            val root = File(directory, "files").apply { mkdir() }
            val cache = File(directory, "cache").apply { mkdir() }
            test(root, cache)
        } finally { directory.deleteRecursively() }
    }
}
