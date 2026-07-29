package dev.pam.nativeapp.render

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PamImageFileTest {
    @Test
    fun resolvesAnExistingSandboxFile() {
        val root = Files.createTempDirectory("pam-image-root")
        val image = root.resolve("imports/photo one.jpg")
        image.parent.createDirectories()
        image.createFile()

        assertEquals(
            image.toFile().canonicalFile,
            resolvePamImageFile(root.toFile(), "pam-file:///imports/photo%20one.jpg"),
        )
    }

    @Test
    fun rejectsAuthorityTraversalAndMissingFiles() {
        val root = Files.createTempDirectory("pam-image-root").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            resolvePamImageFile(root, "pam-file://host/photo.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolvePamImageFile(root, "pam-file:///../photo.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolvePamImageFile(root, "pam-file:///missing.jpg")
        }
    }
}
