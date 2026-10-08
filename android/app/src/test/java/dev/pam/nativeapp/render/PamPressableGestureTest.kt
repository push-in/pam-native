package dev.pam.nativeapp.render

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamPressableGestureTest {
    @Test
    fun recognizedGestureCancelsCompetingPressSemantics() {
        assertTrue(gestureRecognitionCancelsPress(recognized = true, pressActive = true))
        assertFalse(gestureRecognitionCancelsPress(recognized = false, pressActive = true))
        assertFalse(gestureRecognitionCancelsPress(recognized = true, pressActive = false))
    }

    @Test
    fun multiPointerGestureClaimsItsStreamBeforeAncestorScrollInterception() {
        assertTrue(gestureRequiresParentInterception(MotionEvent.ACTION_POINTER_DOWN, true))
        assertFalse(gestureRequiresParentInterception(MotionEvent.ACTION_DOWN, true))
        assertFalse(gestureRequiresParentInterception(MotionEvent.ACTION_POINTER_DOWN, false))
    }

    @Test
    fun focalZoomKeepsTheContentPointUnderTheFingers() {
        val pivot = 160f
        val start = 240f
        val base = 20f
        // Content point under the focus: (focal - pivot - t) / scale.
        val zoomed = focalZoomTranslation(start, start, pivot, base, 2f)
        assertEquals((start - pivot - base) / 1.5f, (start - pivot - zoomed) / 3f, 0.0001f)
        // Moving the fingers by 30 carries the content along.
        assertEquals(zoomed + 30f, focalZoomTranslation(start + 30f, start, pivot, base, 2f), 0.0001f)
        // No zoom, no movement: the translation stays.
        assertEquals(base, focalZoomTranslation(start, start, pivot, base, 1f), 0.0001f)
    }
}
