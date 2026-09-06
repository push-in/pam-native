package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamDrawerLayoutTest {
    @Test
    fun permanentDrawerReservesContentInsideSafeViewport() {
        assertEquals(
            0 to 1_482,
            permanentDrawerContentClip(0, 2_154, 672, right = false),
        )
        assertEquals(
            672 to 2_154,
            permanentDrawerContentClip(0, 2_154, 672, right = true),
        )
        assertEquals(
            126 to 1_608,
            permanentDrawerContentClip(126, 2_280, 672, right = false),
        )
    }

    @Test
    fun permanentPresentationDoesNotChangeRequestedOpenState() {
        val requestedOpen = false

        assertFalse(drawerIsVisuallyOpen(requestedOpen, permanent = false))
        assertTrue(drawerIsVisuallyOpen(requestedOpen, permanent = true))
        assertFalse(drawerIsVisuallyOpen(requestedOpen, permanent = false))
    }

    @Test
    fun explicitlyOpenedDrawerRemainsOpenAcrossPresentationChanges() {
        val requestedOpen = true

        assertTrue(drawerIsVisuallyOpen(requestedOpen, permanent = false))
        assertTrue(drawerIsVisuallyOpen(requestedOpen, permanent = true))
        assertTrue(drawerIsVisuallyOpen(requestedOpen, permanent = false))
    }
}
