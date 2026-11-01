package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamScrollPagingTest {
    @Test
    fun pagingMovesAtMostOnePageFromGestureOrigin() {
        assertEquals(800, pamOnePageTarget(400, 1_480, 5_000, 400, 2_000))
    }

    @Test
    fun pagingUsesDisplacementWhenVelocityIsLow() {
        assertEquals(1_200, pamOnePageTarget(800, 900, 0, 400, 2_000))
        assertEquals(800, pamOnePageTarget(800, 840, 0, 400, 2_000))
    }

    @Test
    fun pagingClampsToPartialFinalPage() {
        assertEquals(1_850, pamOnePageTarget(1_600, 1_850, 1_000, 400, 1_850))
    }

    @Test
    fun nonPagingExtentPreservesClampedPosition() {
        assertEquals(600, pamOnePageTarget(0, 900, 0, 0, 600))
    }

    @Test
    fun flingPagesFromThePageTheGestureStartedOn() {
        // Dragged 700 px of a 1000 px page before the fling: one page, not two.
        assertEquals(1_000, pamOnePageTarget(0, 700, 4_000, 1_000, 4_000))
    }

    @Test
    fun longAnimatedScrollJumpsToOneViewportFromTheTarget() {
        // Two viewports or less: glide the whole way.
        assertEquals(1_000, longGlideStart(current = 1_000, target = 0, viewport = 800))
        // Far below the top: start one screen above the target.
        assertEquals(800, longGlideStart(current = 160_000, target = 0, viewport = 800))
        // Far above the end: start one screen before it.
        assertEquals(49_200, longGlideStart(current = 0, target = 50_000, viewport = 800))
        assertEquals(5_000, longGlideStart(current = 5_000, target = 0, viewport = 0))
    }
}
