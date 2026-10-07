package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamPrefetchRampTest {
    @Test
    fun extraSpaceGrowsOneRowPerPass() {
        var applied = 0
        val passes = mutableListOf<Int>()
        repeat(5) {
            applied = rampExtraLayoutSpace(applied, target = 1000, step = 270)
            passes += applied
        }
        assertEquals(listOf(270, 540, 810, 1000, 1000), passes)
    }

    @Test
    fun extraSpaceShrinksAtOnceAndWithoutStepAppliesTheTarget() {
        assertEquals(270, rampExtraLayoutSpace(2700, target = 270, step = 270))
        assertEquals(3240, rampExtraLayoutSpace(0, target = 3240, step = 0))
    }

    @Test
    fun onlyRowsAddedAfterTheExistingOnesAreAnAppend() {
        assertTrue(isAppend(listOf(1L, 2L), listOf(1L, 2L, 3L)))
        assertFalse(isAppend(emptyList(), listOf(1L)))
        assertFalse(isAppend(listOf(1L, 2L), listOf(0L, 1L, 2L)))
        assertFalse(isAppend(listOf(1L, 2L), listOf(1L, 2L)))
        assertFalse(isAppend(listOf(1L, 2L), listOf(2L, 1L, 3L)))
    }

    @Test
    fun rowsAddedBeforeTheFirstKeptRowArePrepended() {
        assertTrue(rowsAddedBefore(listOf(1L, 2L), listOf(-1L, 0L, 1L, 2L)))
        // A bounded window: older rows join above while the newest leave.
        assertTrue(rowsAddedBefore(listOf(1L, 2L, 3L), listOf(-1L, 0L, 1L)))
        assertFalse(rowsAddedBefore(emptyList(), listOf(1L)))
        assertFalse(rowsAddedBefore(listOf(1L, 2L), listOf(1L, 2L, 3L)))
        assertFalse(rowsAddedBefore(listOf(1L, 2L), listOf(1L, 2L)))
        assertFalse(rowsAddedBefore(listOf(1L, 2L), listOf(2L, 1L)))
    }

    @Test
    fun rowsAddedAfterTheLastKeptRowAreAppended() {
        assertTrue(rowsAddedAfter(listOf(1L, 2L), listOf(1L, 2L, 3L)))
        assertTrue(rowsAddedAfter(listOf(1L, 2L, 3L), listOf(3L, 4L, 5L)))
        assertFalse(rowsAddedAfter(listOf(1L, 2L), listOf(0L, 1L, 2L)))
        assertFalse(rowsAddedAfter(listOf(1L, 2L), listOf(1L, 2L)))
        assertFalse(rowsAddedAfter(listOf(1L, 2L), listOf(2L, 1L)))
    }
}
