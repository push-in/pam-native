package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamLetterSpacingTest {
    @Test
    fun convertsLogicalPointsToAndroidEmUnits() {
        assertEquals(0.03f, resolvedAndroidLetterSpacing(0.6f, 20f), 0.0001f)
        assertEquals(-0.02f, resolvedAndroidLetterSpacing(-0.28f, 14f), 0.0001f)
        assertEquals(0f, resolvedAndroidLetterSpacing(0f, 0f), 0.0001f)
    }
}
