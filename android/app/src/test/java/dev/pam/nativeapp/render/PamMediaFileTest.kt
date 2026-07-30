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

    @Test
    fun cachePassCannotRestoreOpaqueSandboxScheme() {
        assertEquals(true, shouldUseResolvedMediaUri(false, "pam-file"))
        assertEquals(true, shouldUseResolvedMediaUri(true, null))
        assertEquals(false, shouldUseResolvedMediaUri(false, "file"))
        assertEquals(false, shouldUseResolvedMediaUri(false, "https"))
    }

    @Test
    fun coverUniformlyScalesPortraitVideoPastContainerBounds() {
        val scale = resolveVideoScale(1, 1028, 1024, 576, 1024)

        assertEquals(1028f / 576f, scale.first, 0.0001f)
        assertEquals(1028f / 576f, scale.second, 0.0001f)
    }

    @Test
    fun containKeepsNativeVideoAspectFitScale() {
        val scale = resolveVideoScale(2, 1028, 1024, 576, 1024)

        assertEquals(1f, scale.first, 0f)
        assertEquals(1f, scale.second, 0f)
    }

    @Test
    fun stretchIndependentlyScalesBothAxes() {
        val scale = resolveVideoScale(3, 1028, 1024, 576, 1024)

        assertEquals(1028f / 576f, scale.first, 0.0001f)
        assertEquals(1f, scale.second, 0.0001f)
    }
}
