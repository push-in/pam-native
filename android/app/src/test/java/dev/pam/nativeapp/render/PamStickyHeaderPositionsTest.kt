package dev.pam.nativeapp.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PamStickyHeaderPositionsTest {
    @Test
    fun stickyPositionsFollowTheCellOrder() {
        val ids = listOf(10L, 11L, 12L, 13L, 14L, 15L)
        assertArrayEquals(intArrayOf(0, 3, 5), stickyHeaderPositions(ids, setOf(15L, 10L, 13L)))
        assertArrayEquals(intArrayOf(), stickyHeaderPositions(ids, emptySet()))
        assertArrayEquals(intArrayOf(), stickyHeaderPositions(ids, setOf(99L)))
    }

    @Test
    fun theLastHeaderAtOrBeforeTheTopRowIsPinned() {
        val positions = intArrayOf(0, 3, 5)
        assertEquals(0, lastAtOrBefore(positions, 0))
        assertEquals(0, lastAtOrBefore(positions, 2))
        assertEquals(1, lastAtOrBefore(positions, 3))
        assertEquals(1, lastAtOrBefore(positions, 4))
        assertEquals(2, lastAtOrBefore(positions, 40))
        assertEquals(-1, lastAtOrBefore(intArrayOf(2, 4), 1))
        assertEquals(-1, lastAtOrBefore(intArrayOf(), 7))
    }
}
