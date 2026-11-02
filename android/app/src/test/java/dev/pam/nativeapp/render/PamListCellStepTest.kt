package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamListCellStepTest {
    @Test fun cellsStepByTheirFlooredPixelHeightLikeFlashList() {
        // 136.67 dp at 2.625 = 358.76 px: FlashList steps 358, not 359.
        assertEquals(358, listCellPixels(136.67f, 2.625f))
        // Three rows of 20.4 dp (53.55 px) end at 159 px, not 162.
        assertEquals(159, 3 * listCellPixels(20.4f, 2.625f))
    }

    @Test fun aWholePixelLeftAHairShortByFloatMathStaysOnIt() {
        assertEquals(42, listCellPixels(16f, 2.625f))
        assertEquals(359, listCellPixels(358.99997f, 1f))
    }

    @Test fun anEmptyCellKeepsOnePixel() {
        assertEquals(1, listCellPixels(0f, 2.625f))
    }
}
