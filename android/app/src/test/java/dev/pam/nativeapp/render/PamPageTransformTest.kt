package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stories cube: same face math as the RN StoryCubePager (`cubeFace`). */
class PamPageTransformTest {
    @Test
    fun frontFaceIsFlat() {
        val face = pamCubeFace(0f)
        assertEquals(0f, face.angleDeg, 0f)
        assertEquals(0f, face.shade, 0f)
    }

    @Test
    fun leftFaceTurnsAroundItsRightEdgeWithNegativeAngle() {
        val face = pamCubeFace(-0.5f)
        assertEquals(-45f, face.angleDeg, 1e-4f)
        assertEquals(1f, face.pivotFraction, 0f)
        assertEquals(0.275f, face.shade, 1e-4f)
    }

    @Test
    fun rightFaceTurnsAroundItsLeftEdgeWithPositiveAngle() {
        val face = pamCubeFace(0.25f)
        assertEquals(22.5f, face.angleDeg, 1e-4f)
        assertEquals(0f, face.pivotFraction, 0f)
    }

    @Test
    fun positionsBeyondOneFaceAreClamped() {
        assertEquals(90f, pamCubeFace(3f).angleDeg, 0f)
        assertEquals(0.55f, pamCubeFace(-7f).shade, 1e-4f)
    }

    @Test
    fun offsetGlideInterpolatesWholePixelsAndClampsTheFraction() {
        assertEquals(0, pamLerpOffset(0, 1080, 0f))
        assertEquals(540, pamLerpOffset(0, 1080, 0.5f))
        assertEquals(1080, pamLerpOffset(0, 1080, 1.4f))
        assertEquals(1080, pamLerpOffset(2160, 1080, 1f))
    }

    @Test
    fun reRequestingTheGlideTargetKeepsTheGlideAndANewTargetDoesNot() {
        // Same target re-sent (e.g. with the duration reset to 0): keep gliding.
        assertTrue(pamRequestKeepsGlide(true, 1080, false, 0, 1080, 0))
        // Nothing requested (a plain relayout): keep gliding.
        assertTrue(pamRequestKeepsGlide(false, 0, false, 0, 1080, 0))
        // A new page: the glide yields to the new request.
        assertFalse(pamRequestKeepsGlide(true, 2160, false, 0, 1080, 0))
        assertFalse(pamRequestKeepsGlide(true, 1080, true, 40, 1080, 0))
    }

    @Test
    fun perspectiveMatchesReactNativeCameraDistance() {
        // RN BaseViewManager: density^2 * perspective * sqrt(5).
        assertEquals(2.75f * 2.75f * 864f * 2.2360680f, pamCameraDistance(864f, 2.75f), 0.5f)
    }
}
