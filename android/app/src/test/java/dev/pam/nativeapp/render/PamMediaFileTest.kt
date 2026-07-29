package dev.pam.nativeapp.render

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PamMediaFileTest {
    @Test
    fun resolvesEncodedFileInsideSandbox() {
        val root = Files.createTempDirectory("pam-media-root").toFile()
        val media = root.resolve("imports/video one.mp4").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        assertEquals(
            media.canonicalFile,
            resolvePamMediaFile(root, "pam-file:///imports/video%20one.mp4"),
        )
    }

    @Test
    fun rejectsAuthorityTraversalAndMissingFiles() {
        val root = Files.createTempDirectory("pam-media-root").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            resolvePamMediaFile(root, "pam-file://host/video.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolvePamMediaFile(root, "pam-file:///../video.mp4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolvePamMediaFile(root, "pam-file:///missing.mp4")
        }
    }
}
