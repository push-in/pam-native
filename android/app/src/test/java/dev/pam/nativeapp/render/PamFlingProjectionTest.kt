package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamFlingProjectionTest {
    private val friction = 0.015f

    @Test
    fun aFasterFlingProjectsFurtherAndRestIsZero() {
        assertEquals(0f, splineFlingDistancePx(0f, 2.625f, friction))
        val slow = splineFlingDistancePx(2_000f, 2.625f, friction)
        val fast = splineFlingDistancePx(12_000f, 2.625f, friction)
        assertTrue(slow > 0f)
        assertTrue(fast > slow * 3f)
        // Direction does not change the distance.
        assertEquals(fast, splineFlingDistancePx(-12_000f, 2.625f, friction), 0.01f)
        // A typical fast phone fling travels several screens (2400 px each).
        assertTrue(fast in 4_000f..40_000f)
    }

    @Test
    fun aFlingAsksForTheNextPageAsSoonAsItProjectsPastTheThreshold() {
        // At rest: RN threshold rows only.
        assertEquals(1, endReachedTriggerRows(2, 0.5, 0f, 1200f))
        assertEquals(4, endReachedTriggerRows(2, 2.0, 0f, 1200f))
        // A fling still projecting 6000 px over 1200 px rows adds 5 rows.
        assertEquals(6, endReachedTriggerRows(2, 0.5, 6_000f, 1200f))
    }
}
