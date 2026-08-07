package dev.pam.nativeapp.render

import android.view.MotionEvent
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
}
