package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamPixelSnappingTest {
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
