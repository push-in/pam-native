package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamTextScaleTest {
    @Test
    fun multiplierAppliesOnTopOfTheCappedSystemScale() {
        assertEquals(1.15f, PamTextScale.combine(1.15f, 0f, 1f), 1e-6f)
        assertEquals(2.6f, PamTextScale.combine(1.3f, 0f, 2f), 1e-6f)
        assertEquals(1.3f * 1.6f, PamTextScale.combine(1.3f, 1.6f, 2f), 1e-6f)
        assertEquals(0.9f * 1.2f, PamTextScale.combine(0.9f, 1.6f, 1.2f), 1e-6f)
        assertEquals(1f, PamTextScale.combine(1f, 0f, Float.NaN), 1e-6f)
    }

    @Test
    fun rangesAreValidated() {
        assertTrue(PamTextScale.isValidMultiplier(0.5f))
        assertTrue(PamTextScale.isValidMultiplier(3f))
        assertFalse(PamTextScale.isValidMultiplier(0.4f))
        assertFalse(PamTextScale.isValidMultiplier(Float.POSITIVE_INFINITY))
        assertTrue(PamTextScale.isValidCap(0f))
        assertTrue(PamTextScale.isValidCap(1.6f))
        assertFalse(PamTextScale.isValidCap(0.8f))
    }
}
