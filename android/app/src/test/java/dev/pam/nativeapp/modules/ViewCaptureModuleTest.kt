package dev.pam.nativeapp.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewCaptureModuleTest {
    @Test
    fun pixelRatioIsRelativeToTheScreenDensityAndCapped() {
        // 3 output pixels per point on a 2.625 density screen.
        assertEquals(3f / 2.625f, ViewCaptureModule.captureScale(882, 1680, 3.0, 2.625f), 0.0001f)
        // No ratio: one pixel per view pixel.
        assertEquals(1f, ViewCaptureModule.captureScale(1080, 1920, 0.0, 2.75f), 0.0001f)
        assertEquals(1f, ViewCaptureModule.captureScale(1080, 1920, Double.NaN, 2.75f), 0.0001f)
        // 8x of a 1000 px view would exceed 4096 px per side.
        assertEquals(4096f / 1000f, ViewCaptureModule.captureScale(1000, 300, 8.0, 1f), 0.0001f)
    }

    @Test
    fun capturesStayInsidePrivateFolders() {
        assertEquals("view-captures", ViewCaptureModule.safeDirectory(""))
        assertEquals("story-stickers/backgrounds".replace('/', java.io.File.separatorChar), ViewCaptureModule.safeDirectory("/story-stickers/backgrounds/"))
        for (unsafe in listOf("../x", "a/../b", "a/./b", "a b", "a/\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { ViewCaptureModule.safeDirectory(unsafe) }
        }
    }
}
