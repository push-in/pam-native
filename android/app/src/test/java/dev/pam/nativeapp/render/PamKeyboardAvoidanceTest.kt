package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamKeyboardAvoidanceTest {
    @Test
    fun usesAnimatedImeInsetWhenWindowDoesNotResize() {
        assertEquals(
            840,
            resolvedKeyboardInset(
                platformInset = 840,
                baselineHeight = 2_100,
                currentHeight = 2_100,
                minimumKeyboardHeight = 240,
            ),
        )
    }

    @Test
    fun infersKeyboardInsetFromAdjustResizeWhenImeInsetIsConsumed() {
        assertEquals(
            820,
            resolvedKeyboardInset(
                platformInset = 0,
                baselineHeight = 2_100,
                currentHeight = 1_280,
                minimumKeyboardHeight = 240,
            ),
        )
    }

    @Test
    fun ignoresSmallWindowChangesThatAreNotAKeyboard() {
        assertEquals(
            0,
            resolvedKeyboardInset(
                platformInset = 0,
                baselineHeight = 2_100,
                currentHeight = 2_000,
                minimumKeyboardHeight = 240,
            ),
        )
    }
}
