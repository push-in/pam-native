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

    @Test fun headerRowsStepToTheirYogaRoundedOffsetsNotFlooredHeights() {
        // Summary 237.67 dp then highlights 93.76 dp at 2.625: floored steps
        // 623 + 246 end the header at 869 px; one Yoga view puts the rows at
        // round(623.88) = 624 and round(870.0) = 870.
        val steps = headerRunPixelSteps(doubleArrayOf(0.0, 237.6667, 331.4267), 2.625f)
        assertEquals(624, steps[0])
        assertEquals(870, steps[0] + steps[1])
        // The step survives listCellPixels' floor.
        assertEquals(624, listCellPixels(((steps[0] + 0.5) / 2.625f).toFloat(), 2.625f))
    }

    @Test fun aWholePixelOriginRoundsTheStepLikeFabric() {
        // Notifications list content at 411.25 px: 135.5 px header -> 136,
        // 215.875 px row -> 216, 260.5 px row -> 261 (RN on the S10).
        val origin = listPixelOrigin(156.66667f, 2.625f)
        assertEquals(0.25, origin, 0.001)
        assertEquals(136, listCellPixels(51.619048f, 2.625f, origin))
        assertEquals(216, listCellPixels(82.2381f, 2.625f, origin))
        assertEquals(261, listCellPixels(99.2381f, 2.625f, origin))
        assertEquals(216, listCellPixels(82.2381f, 2.625f, 0.0))
    }

    @Test fun aHalfPixelOriginKeepsTheFlooredStep() {
        // Chat/profile lists start at 94.67 dp = 248.5 px.
        val origin = listPixelOrigin(94.666664f, 2.625f)
        assertEquals(0.5, origin, 0.001)
        assertEquals(358, listCellPixels(136.67f, 2.625f, origin))
        assertEquals(215, listCellPixels(82.2381f, 2.625f, origin))
    }

    @Test fun aPixelStepRoundTripsThroughItsExtent() {
        for (origin in doubleArrayOf(0.0, 0.25, 0.5, 0.75)) {
            for (step in intArrayOf(1, 42, 135, 330)) {
                assertEquals(step, listCellPixels(extentForPixelStep(step, 2.625f, origin), 2.625f, origin))
            }
        }
    }
}
