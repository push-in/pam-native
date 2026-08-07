package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamTranslatedTouchTargetTest {
    @Test
    fun `absolute overlay is drawn above translated input branch`() {
        assertTrue(
            isPamViewDrawnAbove(
                candidateZ = 100f,
                candidateIndex = 0,
                branchZ = 0f,
                branchIndex = 1,
            ),
        )
        assertTrue(
            isPamViewDrawnAbove(
                candidateZ = 0f,
                candidateIndex = 2,
                branchZ = 0f,
                branchIndex = 1,
            ),
        )
        assertFalse(
            isPamViewDrawnAbove(
                candidateZ = 0f,
                candidateIndex = 0,
                branchZ = 0f,
                branchIndex = 1,
            ),
        )
    }

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

    @Test
    fun `interactive replacement remains eligible while ime stays open`() {
        assertTrue(
            shouldRegisterTranslatedTouchTarget(
                isInput = false,
                isPressable = true,
                includePressables = true,
            ),
        )
    }

    @Test
    fun `message scroller does not occlude translated composer action`() {
        assertFalse(
            isEligibleTranslatedTouchOccluder(
                isScrollContainer = true,
                containsInteractiveTarget = true,
            ),
        )
        assertTrue(
            isEligibleTranslatedTouchOccluder(
                isScrollContainer = false,
                containsInteractiveTarget = true,
            ),
        )
    }
}
