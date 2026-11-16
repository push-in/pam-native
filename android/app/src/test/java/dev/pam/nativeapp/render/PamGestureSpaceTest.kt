package dev.pam.nativeapp.render

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamGestureSpaceTest {
    @Test
    fun withoutScaleOrRotationTheScreenDeltaIsUntouched() {
        val linear = FloatArray(4).also(PamGestureSpace::identity)
        // A view moved by translation, left/top or scroll contributes [1, 0, 0, 1].
        PamGestureSpace.concatOuter(1f, 0f, 0f, 1f, linear)
        assertTrue(PamGestureSpace.isIdentity(linear))
    }

    @Test
    fun aScaledAncestorDividesTheDisplacement() {
        val linear = chain(scaleRotate(0.5f, 0f), scaleRotate(2f, 0f), scaleRotate(0.5f, 0f))
        assertEquals(0.5f, linear[0], 1e-6f)
        assertTrue(PamGestureSpace.invert(linear))
        assertArrayEquals(floatArrayOf(-160f, 24f), mapped(linear, -80f, 12f), 1e-4f)
    }

    @Test
    fun aRotatedAncestorTurnsTheDisplacementBack() {
        // 90° clockwise: the detector's x axis points down the screen, so a
        // finger moving left travels down the detector's y axis.
        val linear = chain(scaleRotate(1f, 90f))
        assertTrue(PamGestureSpace.invert(linear))
        assertArrayEquals(floatArrayOf(0f, 80f), mapped(linear, -80f, 0f), 1e-4f)
        assertArrayEquals(floatArrayOf(40f, 0f), mapped(linear, 0f, 40f), 1e-4f)
    }

    @Test
    fun aStickerBoxRoundTripsTheLocalVector() {
        // Story sticker: box scaled 1.6 and rotated -25°, inside a canvas at 0.8.
        val toScreen = chain(scaleRotate(1f, 0f), scaleRotate(1.6f, -25f), scaleRotate(0.8f, 0f))
        val local = floatArrayOf(30f, -12f)
        val screen = local.copyOf().also { PamGestureSpace.map(toScreen, it) }
        assertTrue(PamGestureSpace.invert(toScreen))
        assertArrayEquals(local, mapped(toScreen, screen[0], screen[1]), 1e-3f)
    }

    @Test
    fun outerTransformsApplyAfterInnerOnes() {
        // Inner non-uniform scale, outer rotation: order matters.
        val linear = chain(floatArrayOf(2f, 0f, 0f, 1f), scaleRotate(1f, 90f))
        // Local (1, 0) → scaled (2, 0) → rotated (0, 2).
        assertArrayEquals(floatArrayOf(0f, 2f), mapped(linear, 1f, 0f), 1e-5f)
    }

    @Test
    fun aCollapsedAncestorKeepsTheScreenDelta() {
        val linear = chain(scaleRotate(0f, 0f))
        val before = linear.copyOf()
        assertFalse(PamGestureSpace.invert(linear))
        assertArrayEquals(before, linear, 0f)
        val nan = floatArrayOf(Float.NaN, 0f, 0f, 1f)
        assertFalse(PamGestureSpace.invert(nan))
    }

    /** Innermost first, as [PamGestureSpace.screenToLocal] walks up from the detector. */
    private fun chain(vararg linears: FloatArray): FloatArray {
        val out = FloatArray(4).also(PamGestureSpace::identity)
        for (m in linears) PamGestureSpace.concatOuter(m[0], m[1], m[2], m[3], out)
        return out
    }

    /** The linear part of an Android View matrix with a uniform scale and a clockwise rotation. */
    private fun scaleRotate(scale: Float, degrees: Float): FloatArray {
        val radians = Math.toRadians(degrees.toDouble())
        val c = (cos(radians) * scale).toFloat()
        val s = (sin(radians) * scale).toFloat()
        return floatArrayOf(c, -s, s, c)
    }

    private fun mapped(linear: FloatArray, x: Float, y: Float): FloatArray =
        floatArrayOf(x, y).also { PamGestureSpace.map(linear, it) }
}
