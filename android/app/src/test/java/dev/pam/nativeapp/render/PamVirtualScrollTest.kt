package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamVirtualScrollTest {
    @Test
    fun resolvesLogicalOffsetAcrossVariableExtentCells() {
        assertEquals(VirtualScrollPosition(0, 0), virtualScrollPosition(listOf(120, 80, 240), 0))
        assertEquals(VirtualScrollPosition(0, 119), virtualScrollPosition(listOf(120, 80, 240), 119))
        assertEquals(VirtualScrollPosition(1, 0), virtualScrollPosition(listOf(120, 80, 240), 120))
        assertEquals(VirtualScrollPosition(2, 30), virtualScrollPosition(listOf(120, 80, 240), 230))
    }

    @Test
    fun clampsOffsetsPastTheLastCell() {
        assertEquals(VirtualScrollPosition(1, 79), virtualScrollPosition(listOf(120, 80), 500))
        assertEquals(VirtualScrollPosition(0, 0), virtualScrollPosition(emptyList(), 500))
    }
}
