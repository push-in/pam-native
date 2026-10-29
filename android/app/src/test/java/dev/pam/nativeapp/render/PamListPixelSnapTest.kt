package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class PamListPixelSnapTest {
    private val density = 2.625f

    @Test fun rowsSnapCumulativelyLikeYogaWithoutDrift() {
        // 13 rows of 72.4 dp: rounding each height alone (190 px) drifts 3 px.
        val ids = (1L..13L).toList()
        val extents = ids.associateWith { 72.4f }
        val snapped = snappedPixelExtents(ids, extents, density).extents
        val total = ids.sumOf { snapped.getValue(it) }
        assertEquals((13 * 72.4 * density).roundToInt(), total)
        var top = 0
        ids.forEachIndexed { index, id ->
            assertEquals("row $index top", (index * 72.4 * density).roundToInt(), top)
            top += snapped.getValue(id)
        }
    }

    @Test fun rowsJoiningAboveOrBelowKeepTheExtentsOfTheRowsShown() {
        val shown = listOf(10L, 11L, 12L, 13L)
        val extents = mutableMapOf(10L to 61.3f, 11L to 44.7f, 12L to 90.1f, 13L to 33.3f)
        val first = snappedPixelExtents(shown, extents, density)
        extents[1L] = 17.15f
        extents[2L] = 23.9f
        extents[20L] = 51.05f
        val grown = snappedPixelExtents(listOf(1L, 2L) + shown + 20L, extents, density, first.phases)
        for (id in shown) assertEquals("row $id", first.extents.getValue(id), grown.extents.getValue(id))
        // The joined rows are snapped against the same grid (no gap at the seam).
        val above = grown.extents.getValue(1L) + grown.extents.getValue(2L)
        val expected = (first.phases.getValue(10L) - (17.15 + 23.9) * density).let {
            Math.floor(first.phases.getValue(10L) + 0.5) - Math.floor(it + 0.5)
        }.toInt()
        assertEquals(expected, above)
    }

    @Test fun emptyRowsKeepTheAdapterMinimumOutsideTheOffset() {
        val ids = listOf(1L, 2L, 3L)
        val snapped = snappedPixelExtents(ids, mapOf(1L to 10f, 2L to 0f, 3L to 10f), density).extents
        assertEquals(1, snapped.getValue(2L))
        assertEquals((20 * density).roundToInt(), snapped.getValue(1L) + snapped.getValue(3L))
    }

    @Test fun aRootWithoutMarginsFillsItsSnappedSlot() {
        assertTrue(cellRootFillsSlot(120.5f, 72.4f, 120.5f, 72.4f))
        assertFalse(cellRootFillsSlot(124.5f, 64.4f, 120.5f, 72.4f))
    }
}
