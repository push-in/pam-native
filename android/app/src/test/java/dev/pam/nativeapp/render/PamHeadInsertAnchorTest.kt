package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamHeadInsertAnchorTest {
    @Test
    fun rowsPrependedAboveTheFirstVisibleRowNeedNoExtraAnchor() {
        // RecyclerView keeps its first visible child in place by itself.
        assertNull(headInsertAnchor(listOf(1L, 2L, 3L), listOf(-2L, -1L, 1L, 2L, 3L), firstVisible = 0))
    }

    @Test
    fun rowsInsertedRightAfterAVisibleHeaderAnchorTheFollowingRow() {
        // [header, a, b] -> [header, x, y, a, b] with the header on screen.
        assertEquals(10L, headInsertAnchor(listOf(99L, 10L, 11L), listOf(99L, 1L, 2L, 10L, 11L), firstVisible = 0))
        // Two leading rows (header, loader).
        assertEquals(10L, headInsertAnchor(listOf(98L, 99L, 10L), listOf(98L, 99L, 1L, 10L), firstVisible = 0))
    }

    @Test
    fun insertsAwayFromTheHeadOrBelowTheViewportAreLeftAlone() {
        assertNull(headInsertAnchor(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L, 4L), firstVisible = 0))
        val previous = (1L..20L).toList()
        val next = previous.take(10) + listOf(100L) + previous.drop(10)
        assertNull(headInsertAnchor(previous, next, firstVisible = 8))
        assertNull(headInsertAnchor(previous, next, firstVisible = -1))
    }
}
