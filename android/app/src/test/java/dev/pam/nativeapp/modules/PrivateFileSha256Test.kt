package dev.pam.nativeapp.modules

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PrivateFileSha256Test {
    @Test fun hashesKnownVectorsAndFileBeyondBridgeLimit() = temporary { root ->
        val file = File(root, "document.bin")
        file.writeBytes(byteArrayOf())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PrivateFileSha256.digest(root, file.name))
        file.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PrivateFileSha256.digest(root, file.name))
        file.writeBytes(ByteArray(2 * 1024 * 1024) { 42 })
        assertEquals("52d434cbc3b76fa5ce4480fefe3c7769b6be2708434ed3ea735c31b1213c1d38", PrivateFileSha256.digest(root, file.name))
        assertEquals(2L * 1024 * 1024, file.length())
    }

    @Test fun rejectsUnsafePathsAndExternalSymlinks() = temporary { root ->
        File(root, "folder").mkdir()
        for (path in listOf("", " ", "../outside", "/absolute", "folder", "missing", "a//b", "a/../b", "a\\b", "bad\nname")) {
            assertThrows(IllegalArgumentException::class.java) { PrivateFileSha256.digest(root, path) }
        }
        val outside = File(root.parentFile, "outside").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { PrivateFileSha256.digest(root, "link") }
    }

    @Test fun rejectsOversizeCancellationAndTruncatedSource() = temporary { root ->
        val source = File(root, "large.bin")
        RandomAccessFile(source, "rw").use { it.setLength(PrivateFileSha256.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { PrivateFileSha256.digest(root, source.name) }
        source.writeBytes(ByteArray(128 * 1024))
        assertThrows(IllegalStateException::class.java) { PrivateFileSha256.digest(root, source.name) { true } }
        assertEquals(128L * 1024, source.length())
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            PrivateFileSha256.digest(root, source.name) {
                if (++checks == 2) RandomAccessFile(source, "rw").use { it.setLength(1) }
                false
            }
        }
    }

    private fun temporary(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("pam-file-sha256-test").toFile()
        try { test(File(directory, "files").apply { mkdir() }) }
        finally { directory.deleteRecursively() }
    }
}
