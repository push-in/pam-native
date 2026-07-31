package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamTranslatedTouchTargetTest {
    @Test
    fun `closed ime registers inputs but not underlying pressables`() {
        assertTrue(
            shouldRegisterTranslatedTouchTarget(
                isInput = true,
                isPressable = false,
                includePressables = false,
            ),
        )
        assertFalse(
            shouldRegisterTranslatedTouchTarget(
                isInput = false,
                isPressable = true,
                includePressables = false,
            ),
        )
    }

    @Test
    fun `translated ime container registers both interactive target types`() {
        assertTrue(
            shouldRegisterTranslatedTouchTarget(
                isInput = true,
                isPressable = false,
                includePressables = true,
            ),
        )
        assertTrue(
            shouldRegisterTranslatedTouchTarget(
                isInput = false,
                isPressable = true,
                includePressables = true,
            ),
        )
        assertFalse(
            shouldRegisterTranslatedTouchTarget(
                isInput = false,
                isPressable = false,
                includePressables = true,
            ),
        )
    }
}
