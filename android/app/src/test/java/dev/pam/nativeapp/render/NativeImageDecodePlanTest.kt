package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageDecodePlanTest {
    @Test
    fun feedPhotoInGridCellDecodesToTheCoveringSize() {
        // 1080x1350 photo in a 360x360 px cell: sample 2 (540x675), then an
        // exact scale to 360x450, which still covers the cell.
        val plan = nativeImageDecodePlan(1080, 1350, 360, 360, IMAGE_RESIZE_AUTO, 1f)
        assertEquals(2, plan.sample)
        assertEquals(360, plan.width)
        assertEquals(450, plan.height)
        assertTrue(plan.scaled)
    }

    @Test
    fun landscapeSourceCoversAPortraitView() {
        val plan = nativeImageDecodePlan(4000, 3000, 1080, 2340, IMAGE_RESIZE_AUTO, 1f)
        assertTrue(plan.height >= 2340)
        assertTrue(plan.width >= 1080)
        assertEquals(1, plan.sample)
    }

    @Test
    fun sourceAtTheViewSizeIsDecodedAsIs() {
        val plan = nativeImageDecodePlan(1080, 1920, 1080, 1920, IMAGE_RESIZE_AUTO, 1f)
        assertEquals(NativeImageDecodePlan(1, 1080, 1920, scaled = false), plan)
        val close = nativeImageDecodePlan(1100, 1950, 1080, 1920, IMAGE_RESIZE_AUTO, 1f)
        assertFalse(close.scaled)
        assertEquals(1, close.sample)
    }

    @Test
    fun smallerSourceIsNeverUpscaled() {
        val plan = nativeImageDecodePlan(200, 100, 1080, 1080, IMAGE_RESIZE_RESIZE, 1f)
        assertEquals(NativeImageDecodePlan(1, 200, 100, scaled = false), plan)
    }

    @Test
    fun scaleAndNoneKeepTheSourceResolution() {
        assertEquals(
            NativeImageDecodePlan(1, 3000, 2000, scaled = false),
            nativeImageDecodePlan(3000, 2000, 300, 200, IMAGE_RESIZE_SCALE, 1f),
        )
        assertEquals(
            NativeImageDecodePlan(1, 3000, 2000, scaled = false),
            nativeImageDecodePlan(3000, 2000, 300, 200, IMAGE_RESIZE_NONE, 1f),
        )
    }

    @Test
    fun fullResolutionStaysWithinTheSafePixelBudget() {
        val plan = nativeImageDecodePlan(12000, 9000, 100, 100, IMAGE_RESIZE_NONE, 1f, maxPixels = 33_554_432L)
        assertEquals(2, plan.sample)
        assertTrue(plan.width.toLong() * plan.height <= 33_554_432L)
    }

    @Test
    fun tiledImagesOnlySubsample() {
        val plan = nativeImageDecodePlan(1080, 1350, 360, 360, IMAGE_RESIZE_AUTO, 1f, exactScale = false)
        assertEquals(NativeImageDecodePlan(2, 540, 675, scaled = false), plan)
    }

    @Test
    fun multiplierEnlargesTheTarget() {
        val plan = nativeImageDecodePlan(2000, 2000, 300, 300, IMAGE_RESIZE_AUTO, 2f)
        assertEquals(600, plan.width)
        assertEquals(600, plan.height)
    }

    @Test
    fun scalingIsSkippedWhenSubsamplingIsCloseEnough() {
        // 1440 / 4 = 360: the subsampled decode already is the cover size.
        val plan = nativeImageDecodePlan(1440, 1440, 360, 360, IMAGE_RESIZE_AUTO, 1f)
        assertEquals(NativeImageDecodePlan(4, 360, 360, scaled = false), plan)
    }

    @Test
    fun hardwareStorageForPhotosOnApi28AndLater() {
        assertEquals(
            NativeBitmapStorage.HARDWARE,
            nativeBitmapStorage(sdk = 31, inline = false, allowHardware = true, pixels = 360L * 450, mimeType = "image/jpeg"),
        )
        assertEquals(
            NativeBitmapStorage.ARGB_8888,
            nativeBitmapStorage(sdk = 31, inline = true, allowHardware = true, pixels = 360L * 450, mimeType = "image/png"),
        )
        assertEquals(
            NativeBitmapStorage.ARGB_8888,
            nativeBitmapStorage(sdk = 31, inline = false, allowHardware = true, pixels = 96L * 96, mimeType = "image/jpeg"),
        )
        assertEquals(
            NativeBitmapStorage.ARGB_8888,
            nativeBitmapStorage(sdk = 31, inline = false, allowHardware = false, pixels = 360L * 450, mimeType = "image/png"),
        )
    }

    @Test
    fun opaqueJpegsUseRgb565BeforeApi28() {
        assertEquals(
            NativeBitmapStorage.RGB_565,
            nativeBitmapStorage(sdk = 27, inline = false, allowHardware = true, pixels = 360L * 450, mimeType = "image/jpeg"),
        )
        assertEquals(
            NativeBitmapStorage.ARGB_8888,
            nativeBitmapStorage(sdk = 27, inline = false, allowHardware = true, pixels = 360L * 450, mimeType = "image/png"),
        )
    }

    @Test
    fun memoryCacheFollowsTheHeapClass() {
        assertEquals(32 * 1024 * 1024, nativeImageMemoryCacheBytes(256, lowRam = false))
        assertEquals(24 * 1024 * 1024, nativeImageMemoryCacheBytes(192, lowRam = false))
        assertEquals(64 * 1024 * 1024, nativeImageMemoryCacheBytes(1024, lowRam = false))
        assertEquals(8 * 1024 * 1024, nativeImageMemoryCacheBytes(128, lowRam = true))
        assertEquals(8 * 1024 * 1024, nativeImageMemoryCacheBytes(0, lowRam = false))
    }
}
