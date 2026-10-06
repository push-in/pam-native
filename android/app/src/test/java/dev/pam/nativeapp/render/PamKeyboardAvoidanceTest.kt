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
    fun sheetLiftPutsItsBottomOnTheImeTopWhetherOrNotTheWindowFitsTheNavigationBar() {
        // Window fits the 126 px navigation bar: the content ends above it.
        assertEquals(757, sheetKeyboardLift(contentBottom = 2_274, windowHeight = 2_400, imeInset = 883))
        // Edge-to-edge content reaches the screen bottom.
        assertEquals(883, sheetKeyboardLift(contentBottom = 2_400, windowHeight = 2_400, imeInset = 883))
        assertEquals(0, sheetKeyboardLift(contentBottom = 2_274, windowHeight = 2_400, imeInset = 0))
        assertEquals(0, sheetKeyboardLift(contentBottom = 1_000, windowHeight = 2_400, imeInset = 883))
    }

    @Test
    fun fullScreenModalChildrenKeepTheirFramesUnlessTheySpanTheModal() {
        val match = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val topLeft = android.view.Gravity.TOP or android.view.Gravity.LEFT
        val bottomLeft = android.view.Gravity.BOTTOM or android.view.Gravity.LEFT
        assertEquals(
            ModalChildPlacement(0, 0, match, match, topLeft),
            windowSizedModalChildPlacement(0, 0, 1_080, 2_400, 1_080, 2_400),
        )
        // OptionDialog: the short sheet stays bottom-anchored at its height.
        assertEquals(
            ModalChildPlacement(0, 0, match, 1_056, bottomLeft),
            windowSizedModalChildPlacement(0, 1_344, 1_080, 1_056, 1_080, 2_400),
        )
        // Its tap-to-close area above keeps its top-anchored frame.
        assertEquals(
            ModalChildPlacement(0, 0, match, 1_344, topLeft),
            windowSizedModalChildPlacement(0, 0, 1_080, 1_344, 1_080, 2_400),
        )
        assertEquals(
            ModalChildPlacement(40, 600, 1_000, 800, topLeft),
            windowSizedModalChildPlacement(40, 600, 1_000, 800, 1_080, 2_400),
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
    fun imeTopUsesFullWindowCoordinatesWithoutSubtractingTheTopSafeArea() {
        assertEquals(
            1_517,
            keyboardTopForInset(windowBottom = 2_400, keyboardInset = 883),
        )
    }

    @Test
    fun overlapDoesNotCountParentOverflowBeyondPhysicalWindowTwice() {
        assertEquals(
            686,
            keyboardOverlapForBounds(
                originalBottom = 1_216f,
                windowBottom = 1_080,
                keyboardInset = 686,
                offset = 0,
            ),
        )
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
    fun paddingBehaviorReducesTheChildViewportWithoutMovingTheContainer() {
        assertEquals(true, keyboardAvoidingBehaviorReducesViewport(3))
        assertEquals(
            1_280,
            keyboardAvoidingViewportHeight(
                baseHeight = 2_100,
                keyboardOverlap = 820,
                resize = keyboardAvoidingBehaviorReducesViewport(3),
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
