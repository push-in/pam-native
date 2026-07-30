package dev.pam.nativeapp.render

import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Test

class PamImageFitTest {
    @Test
    fun containScalesSmallAndLargeImagesToFitTheirAuthoredFrame() {
        assertEquals(
            ImageView.ScaleType.FIT_CENTER,
            resolvedImageScaleType(2),
        )
    }

    @Test
    fun otherImageFitsKeepTheirNativeContracts() {
        assertEquals(ImageView.ScaleType.CENTER_CROP, resolvedImageScaleType(1))
        assertEquals(ImageView.ScaleType.FIT_XY, resolvedImageScaleType(3))
        assertEquals(ImageView.ScaleType.CENTER, resolvedImageScaleType(4))
        assertEquals(ImageView.ScaleType.CENTER, resolvedImageScaleType(5))
    }
}
