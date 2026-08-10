package dev.pam.nativeapp.render

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PamKeyboardAvoidanceTest {
    @Test
    fun interactiveBottomSheetKeepsAStableFullWindowForInsets() {
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            modalSoftInputAdjustMode(
                focusKeyboard = false,
                presentation = 3,
                bottomSheetKeyboardBehavior = 1,
            ),
        )
    }

    @Test
    fun interactiveBottomSheetPreservesDetentAndMovesAboveTheIme() {
        assertEquals(
            1_968 to -757f,
            interactiveBottomSheetLayout(
                baseHeight = 1_968,
                keyboardInset = 757,
            ),
        )
    }

    @Test
    fun hiddenImeRestoresTheConfiguredDetent() {
        assertEquals(
            1_968 to 0f,
            interactiveBottomSheetLayout(
                baseHeight = 1_968,
                keyboardInset = 0,
            ),
        )
    }

    @Test
    fun extendBottomSheetKeepsPanWindowSemantics() {
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
            modalSoftInputAdjustMode(
                focusKeyboard = false,
                presentation = 3,
                bottomSheetKeyboardBehavior = 2,
            ),
        )
    }

    @Test
    fun hiddenImeDiscardsAStaleAnimatedInset() {
        assertEquals(0, visibleImeInset(rawInset = 820, visible = false))
    }

    @Test
    fun visibleImePreservesItsCurrentInset() {
        assertEquals(820, visibleImeInset(rawInset = 820, visible = true))
    }

    @Test
    fun resizeBehaviorReducesTheKeyboardAvoidingViewport() {
        assertEquals(
            1_280,
            keyboardAvoidingViewportHeight(
                baseHeight = 2_100,
                keyboardOverlap = 820,
                resize = true,
            ),
        )
    }

    @Test
    fun nonResizeBehaviorPreservesTheKeyboardAvoidingViewport() {
        assertEquals(
            2_100,
            keyboardAvoidingViewportHeight(
                baseHeight = 2_100,
                keyboardOverlap = 820,
                resize = false,
            ),
        )
    }

    @Test
    fun interactiveSheetStopsBelowSafeTopChrome() {
        assertEquals(
            185,
            interactiveKeyboardTranslation(
                keyboardOverlap = 820,
                originalTop = 290,
                minimumTop = 105,
            ),
        )
    }

    @Test
    fun interactiveSheetStillUsesSmallerKeyboardOverlap() {
        assertEquals(
            96,
            interactiveKeyboardTranslation(
                keyboardOverlap = 96,
                originalTop = 290,
                minimumTop = 105,
            ),
        )
    }

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
