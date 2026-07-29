package dev.pam.nativeapp.render

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
}
