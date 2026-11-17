package dev.pam.nativeapp.render

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamPixelSnappingTest {
    @Test
    fun physicalFrameGravityDoesNotResolveThroughStartInRtl() {
        assertEquals(Gravity.TOP or Gravity.LEFT, PAM_PHYSICAL_FRAME_GRAVITY)
        assertFalse(PAM_PHYSICAL_FRAME_GRAVITY and Gravity.RELATIVE_LAYOUT_DIRECTION != 0)
    }

    @Test
    fun aCentredIconSitsWithinAPixelOfTheCentreAtEveryDensity() {
        // A 20dp icon centred in a 44dp button: 12dp gaps are 31.5px at 2.625x.
        // On the absolute grid the two gaps differ by at most one pixel.
        for (density in floatArrayOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
            for (parentStart in floatArrayOf(0f, 13.3f, 101.7f)) {
                val parentExtent = 44f
                val span = snappedPixelSpan(
                    start = parentStart + 12f,
                    extent = 20f,
                    parentStart = parentStart,
                    density = density,
                )
                val parentPixels = yogaPixelGrid((parentStart + parentExtent).toDouble(), density, false, false) -
                    yogaPixelGrid(parentStart.toDouble(), density, false, false)
                val trailing = parentPixels - span.offset - span.extent
                assertTrue("gaps ${span.offset}/$trailing at ${density}x from $parentStart", kotlin.math.abs(span.offset - trailing) <= 1)
            }
        }
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
        for (step in 0 until 20_000) {
            val start = step * 0.0137f
            val plain = snappedPixelSpan(start, width, 0f, density)
            org.junit.Assert.assertTrue("start=$start", plain.extent in 47..49)
            val text = snappedPixelSpan(start, width, 0f, density, preserveContentExtent = true)
            org.junit.Assert.assertTrue("start=$start extent=${text.extent}", text.extent >= 48)
            org.junit.Assert.assertTrue("start=$start extent=${text.extent}", text.extent <= 49)
        }
    }
}

class PamYogaRelativeRoundingTest {
    private val density = 2.625f

    @Test
    fun positionsSnapToTheAbsoluteGridAndTextFloors() {
        // A label 119.75 px below a tile whose own top is fractional (555.3 px):
        // the tile sits at 555, the label at its absolute 675.05 -> 675.
        val parent = 555.3f / density
        val label = parent + 119.75f / density
        assertEquals(120, snappedPixelSpan(label, 45f / density, parent, density, preserveContentExtent = true).offset)
        assertEquals(120, snappedPixelSpan(label, 45f / density, parent, density).offset)
        // Two siblings 37dp in, under a parent at 20dp: both land on 97 px,
        // the pixel of round(37 * 2.625), whatever the parent's rounding.
        val card = 20f
        val title = snappedPixelSpan(37f, 100f, card, density)
        val button = snappedPixelSpan(37f, 60f, card, density)
        assertEquals(title.offset, button.offset)
        assertEquals(97, yogaPixelGrid(card.toDouble(), density, false, false) + title.offset)
    }

    @Test
    fun textTrailingEdgeCeilsAFractionalExtent() {
        val span = snappedPixelSpan(10.2f / density, 20.5f / density, 0f, density, preserveContentExtent = true)
        assertEquals(10, span.offset)
        assertEquals(21, span.extent)
    }

    @Test
    fun secondRowOfAGridKeepsYogasPixel() {
        // Row 2 starts 222.5 px into the tiles container (196.25 + 26.25).
        val container = 555.3f / density
        val row = snappedPixelSpan(container + 222.5f / density, 196.25f / density, container, density)
        assertEquals(223, row.offset)
    }

    @Test
    fun aFlattenedNodeSharesThePixelOfASiblingAtTheSameOffset() {
        // Tab bar: the 2nd tab's pill is 225.45 + 47.1 = 272.55 px from the
        // host. Rounded once it is 273, the pixel an unflattened node at the
        // same offset gets (summing rounded origins gave 272).
        val tab = 225.45 / density
        val pill = 47.1 / density
        assertEquals(273, flattenedYogaOffset(listOf(pill, tab), density, text = false))
        assertEquals(yogaPixelGrid(tab + pill, density, forceCeil = false, forceFloor = false), flattenedYogaOffset(listOf(pill, tab), density, text = false))
        // A card: title and a button in a flattened wrapper, both 37dp in.
        val wrapper = 21.0
        val button = 16.0
        assertEquals(
            yogaPixelGrid(37.0, 2.625f, forceCeil = false, forceFloor = false),
            flattenedYogaOffset(listOf(button, wrapper), 2.625f, text = false),
        )
        // Text floors its position, as Yoga does for text nodes.
        assertEquals(14, flattenedYogaOffset(listOf(10.9 / density, 3.5 / density), density, text = true))
    }

    @Test
    fun yogaPixelGridSnapsFloatNoise() {
        assertEquals(74, yogaPixelGrid(73.49998 / 2.625, density, forceCeil = false, forceFloor = false))
        assertEquals(45, yogaPixelGrid(44.99999 / 2.625, density, forceCeil = true, forceFloor = false))
    }
}

class PamRnStyledTextTest {
    @Test
    fun styledTextUsesSubpixelAdvancesLikeRnCustomStyleSpan() {
        org.junit.Assert.assertTrue(PamTextLayout.rnStyledText("Roboto", 600, false, null))
        org.junit.Assert.assertTrue(PamTextLayout.rnStyledText(null, 600, false, null))
        org.junit.Assert.assertTrue(PamTextLayout.rnStyledText(null, 400, true, null))
        org.junit.Assert.assertTrue(PamTextLayout.rnStyledText(null, 400, false, "tnum"))
        assertFalse(PamTextLayout.rnStyledText(null, 400, false, null))
        assertFalse(PamTextLayout.rnStyledText("", 400, false, ""))
    }
}
