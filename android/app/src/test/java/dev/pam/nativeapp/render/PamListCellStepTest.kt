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

    @Test fun anEndAnchoredRowHangsTheRoundingPixelAboveItsSlot() {
        // 165.7 dp = 434.96 px: Yoga rounds the cell to 435, the list steps 434.
        assertEquals(1, cellEndHang(165.7f, 2.625f))
        // 165.6 dp = 434.7 px -> 435 vs 434: still one.
        assertEquals(1, cellEndHang(165.6f, 2.625f))
        // 165.4 dp = 434.18 px rounds down: no hang.
        assertEquals(0, cellEndHang(165.4f, 2.625f))
        assertEquals(0, cellEndHang(16f, 2.625f))
    }

    @Test fun aFillingRootKeepsTheExtentYogaRoundsItTo() {
        // 178 dp = 467.25 px: the slot steps 467 and the root stays 467;
        // 165.7 dp = 434.96 px: the slot steps 434, the root draws 435.
        assertEquals(467, yogaCellPixels(178f, 2.625f))
        assertEquals(435, yogaCellPixels(165.7f, 2.625f))
        assertEquals(434, listCellPixels(165.7f, 2.625f))
    }
}
