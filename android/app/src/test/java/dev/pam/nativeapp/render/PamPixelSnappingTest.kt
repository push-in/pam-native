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

class PamTailEllipsisCutTest {
    @Test
    fun keepsTheLastGraphemeThatFitsWithTheEllipsisAndItsSpaces() {
        // "hoje à 10" at 10 px per grapheme: offsets 0..9, x = 10 * i.
        val boundaries = IntArray(10) { it }
        val advances = FloatArray(10) { it * 10f }
        // 72 available, ellipsis 12: x + 12 <= 72 keeps up to x = 60 -> "hoje à".
        assertEquals(6, tailEllipsisCut(boundaries, advances, 72f, 12f))
        // 62 available keeps "hoje " with its space (TextUtils trims it).
        assertEquals(5, tailEllipsisCut(boundaries, advances, 62f, 12f))
    }

    @Test
    fun cutsOnlyAtGraphemeBoundaries() {
        // "🔥🖤 x": two surrogate pairs (2 chars each), a space, then x.
        val boundaries = intArrayOf(0, 2, 4, 5, 6)
        val advances = floatArrayOf(0f, 20f, 40f, 45f, 55f)
        assertEquals(5, tailEllipsisCut(boundaries, advances, 58f, 12f))
        assertEquals(2, tailEllipsisCut(boundaries, advances, 40f, 12f))
    }
}
