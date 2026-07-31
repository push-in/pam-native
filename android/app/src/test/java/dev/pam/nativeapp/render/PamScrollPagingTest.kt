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
}
