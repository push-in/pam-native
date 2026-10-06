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
            requireNotNull(parentFile).mkdirs()
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

    @Test
    fun cachePassCannotRestoreOpaqueSandboxScheme() {
        assertEquals(true, shouldUseResolvedMediaUri(false, "pam-file"))
        assertEquals(true, shouldUseResolvedMediaUri(true, null))
        assertEquals(false, shouldUseResolvedMediaUri(false, "file"))
        assertEquals(false, shouldUseResolvedMediaUri(false, "https"))
    }

    @Test
    fun coverFillsTheBoxAndCropsTheOverflow() {
        // 576x1024 portrait in a 1028x1024 box: scaled by 1028/576, height cropped.
        val scale = resolveVideoScale(1, 1028, 1024, 576, 1024)

        assertEquals(1f, scale.first, 0.0001f)
        assertEquals(1028f / 576f, scale.second, 0.0001f)
    }

    @Test
    fun containLetterboxesTheWholeFrame() {
        // A 720x720 loop on a 1080x2280 screen: 1080x1080, centred.
        val square = resolveVideoScale(2, 1080, 2280, 720, 720)
        assertEquals(1f, square.first, 0.0001f)
        assertEquals(1080f / 2280f, square.second, 0.0001f)

        val portrait = resolveVideoScale(2, 1028, 1024, 576, 1024)
        assertEquals(576f / 1028f, portrait.first, 0.0001f)
        assertEquals(1f, portrait.second, 0.0001f)
    }

    @Test
    fun fillStretchesTheFrameToTheBox() {
        val scale = resolveVideoScale(3, 1028, 1024, 576, 1024)

        assertEquals(1f, scale.first, 0f)
        assertEquals(1f, scale.second, 0f)
    }

    @Test
    fun centerKeepsTheNativePixelSize() {
        val scale = resolveVideoScale(4, 1080, 2280, 720, 720)

        assertEquals(720f / 1080f, scale.first, 0.0001f)
        assertEquals(720f / 2280f, scale.second, 0.0001f)
    }
}
