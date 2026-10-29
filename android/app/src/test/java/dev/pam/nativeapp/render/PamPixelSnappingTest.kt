package dev.pam.nativeapp.render

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PamPixelSnappingTest {
    @Test
    fun physicalFrameGravityDoesNotResolveThroughStartInRtl() {
        assertEquals(Gravity.TOP or Gravity.LEFT, PAM_PHYSICAL_FRAME_GRAVITY)
        assertFalse(PAM_PHYSICAL_FRAME_GRAVITY and Gravity.RELATIVE_LAYOUT_DIRECTION != 0)
    }

    @Test
    fun centered_children_share_the_same_physical_center() {
        val density = 2.625f
        val parentExtent = 54f
        val parentCenter = parentExtent / 2f
        val textExtent = 23.5f
        val iconExtent = 17.72f
        val text = snappedPixelSpan(
            start = parentCenter - textExtent / 2f,
            extent = textExtent,
            parentStart = 0f,
            density = density,
        )
        val icon = snappedPixelSpan(
            start = parentCenter - iconExtent / 2f,
            extent = iconExtent,
            parentStart = 0f,
            density = density,
        )

        assertEquals(
            "pixel-edge snapping must preserve the shared center",
            text.offset * 2 + text.extent,
            icon.offset * 2 + icon.extent,
        )
    }

    @Test
    fun adjacent_siblings_share_the_same_rounded_edge() {
        val first = snappedPixelSpan(
            start = 10.2f,
            extent = 19.7f,
            parentStart = 3.1f,
            density = 2.625f,
        )
        val second = snappedPixelSpan(
            start = 29.9f,
            extent = 22.4f,
            parentStart = 3.1f,
            density = 2.625f,
        )

        assertEquals(first.offset + first.extent, second.offset)
    }

    @Test
    fun negative_offsets_round_symmetrically() {
        val span = snappedPixelSpan(
            start = -0.6f,
            extent = 10f,
            parentStart = 0f,
            density = 2f,
        )

        assertEquals(-1, span.offset)
        assertEquals(20, span.extent)
    }
}

class PamTextPixelSnappingTest {
    @Test
    fun measuredTextWidthIsNeverRoundedAwayByItsPosition() {
        val density = 2.625f
        // "QA" measured as 48 px -> 18.285715 dp. Some fractional positions
        // round the two edges apart by only 47 px.
        val width = 48f / density
        var lostPixel = false
        for (step in 0 until 20_000) {
            val start = step * 0.0137f
            val plain = snappedPixelSpan(start, width, 0f, density)
            if (plain.extent < 48) lostPixel = true
            val text = snappedPixelSpan(start, width, 0f, density, preserveContentExtent = true)
            org.junit.Assert.assertTrue("start=$start extent=${text.extent}", text.extent >= 48)
            org.junit.Assert.assertTrue("start=$start extent=${text.extent}", text.extent <= 49)
        }
        org.junit.Assert.assertTrue("fixture must cover the lossy rounding case", lostPixel)
    }
}

class PamYogaTextRoundingTest {
    @Test
    fun yogaPixelEdgeMatchesRoundValueToPixelGrid() {
        assertEquals(221, yogaPixelEdge(221.6f, forceCeil = false, forceFloor = true))
        assertEquals(567, yogaPixelEdge(566.2f, forceCeil = true, forceFloor = false))
        assertEquals(566, yogaPixelEdge(566.2f, forceCeil = false, forceFloor = false))
        assertEquals(567, yogaPixelEdge(566.5f, forceCeil = false, forceFloor = false))
        // Float noise next to a whole pixel snaps to it, whatever is forced.
        assertEquals(345, yogaPixelEdge(344.99998f, forceCeil = false, forceFloor = true))
        assertEquals(345, yogaPixelEdge(345.00002f, forceCeil = true, forceFloor = false))
    }

    @Test
    fun truncatedTextKeepsReactNativesWiderSpan() {
        val density = 2.625f
        // A single-line subtitle measured at 344.4 px starting at 221.6 px:
        // Yoga floors the start (221) and ceils the fractional end (566),
        // 345 px; rounding both edges to nearest gave 222..566 = 344.
        val start = 221.6f / density
        val extent = 344.4f / density
        val text = snappedPixelSpan(start, extent, 0f, density, preserveContentExtent = true)
        assertEquals(221, text.offset)
        assertEquals(345, text.extent)
        val box = snappedPixelSpan(start, extent, 0f, density)
        assertEquals(222, box.offset)
        assertEquals(344, box.extent)
    }

    @Test
    fun wholePixelTextFloorsBothEdges() {
        val density = 2.625f
        val text = snappedPixelSpan(100.5f / density, 345f / density, 0f, density, preserveContentExtent = true)
        assertEquals(100, text.offset)
        assertEquals(345, text.extent)
    }
}
