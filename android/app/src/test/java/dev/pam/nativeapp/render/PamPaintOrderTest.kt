package dev.pam.nativeapp.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamPaintOrderTest {
    /** Child indices in the order Android composites them (stable sort by Z). */
    private fun paintOrder(z: FloatArray): List<Int> = z.indices.sortedWith(compareBy<Int>({ z[it] }, { it }))

    @Test fun withoutElevationZIsTheZIndex() {
        assertArrayEquals(floatArrayOf(0f, 2f, -1f), PamPaintOrder.resolve(floatArrayOf(0f, 2f, -1f), FloatArray(3)), 0f)
    }

    @Test fun earlierElevatedChildPaintsBelowItsLaterSibling() {
        val z = PamPaintOrder.resolve(FloatArray(3), floatArrayOf(21f, 0f, 0f))
        assertArrayEquals(floatArrayOf(21f, 21f, 21f), z, 0f)
        assertTrue(paintOrder(z) == listOf(0, 1, 2))
    }

    @Test fun laterElevatedChildKeepsItsOwnShadowZ() {
        val z = PamPaintOrder.resolve(FloatArray(3), floatArrayOf(0f, 0f, 16f))
        assertArrayEquals(floatArrayOf(0f, 0f, 16f), z, 0f)
    }

    @Test fun elevationNeverDropsBelowItself() {
        val z = PamPaintOrder.resolve(FloatArray(2), floatArrayOf(30f, 8f))
        assertArrayEquals(floatArrayOf(30f, 30f), z, 0f)
        assertTrue(paintOrder(z) == listOf(0, 1))
    }

    @Test fun zIndexStillWinsOverDocumentOrderAndElevation() {
        // Child 0 has zIndex 1 and must cover the elevated later child 1.
        val z = PamPaintOrder.resolve(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 21f, 0f))
        assertTrue(paintOrder(z) == listOf(1, 2, 0))
        assertTrue(z[0] > z[2])
        assertTrue(z[1] >= 21f)
    }

    @Test fun negativeZIndexStaysBelowElevatedSiblings() {
        val z = PamPaintOrder.resolve(floatArrayOf(0f, -1f), floatArrayOf(8f, 0f))
        assertTrue(paintOrder(z) == listOf(1, 0))
        assertArrayEquals(floatArrayOf(8f, -1f), z, 0f)
    }
}
