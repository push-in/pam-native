package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamSafeAreaLayoutTest {
    @Test
    fun columnChildrenKeepTheirDeclaredHeight() {
        assertEquals(
            36 to 0,
            safeAreaChildCrossAxisReduction(
                mainAxisHorizontal = false,
                horizontalInsets = 36,
                verticalInsets = 126,
            ),
        )
    }

    @Test
    fun rowChildrenKeepTheirDeclaredWidth() {
        assertEquals(
            0 to 126,
            safeAreaChildCrossAxisReduction(
                mainAxisHorizontal = true,
                horizontalInsets = 36,
                verticalInsets = 126,
            ),
        )
    }

    @Test
    fun flexViewportExcludesSafeAreaPadding() {
        assertEquals(
            2_274,
            safeAreaFlexViewportExtent(
                layoutExtent = 2_400,
                safeAreaInsets = 126,
            ),
        )
    }

    @Test
    fun consumedSystemBarsUseTheVisibleWindowOnlyOnce() {
        assertEquals(
            2_274,
            safeAreaFlexViewportExtent(
                layoutExtent = 2_400,
                safeAreaInsets = 63,
                windowVisibleExtent = 2_274,
            ),
        )
    }

    @Test
    fun bottomAnchoredAbsoluteChildMovesInsideReducedViewport() {
        assertEquals(
            126 to 0,
            absoluteViewportAdjustment(
                viewportReduction = 126,
                leadingEdge = false,
                trailingEdge = true,
            ),
        )
    }

    @Test
    fun stretchedAbsoluteChildShrinksWithReducedViewport() {
        assertEquals(
            0 to 126,
            absoluteViewportAdjustment(
                viewportReduction = 126,
                leadingEdge = true,
                trailingEdge = true,
            ),
        )
    }

    @Test
    fun topAnchoredAbsoluteChildKeepsItsFrame() {
        assertEquals(
            0 to 0,
            absoluteViewportAdjustment(
                viewportReduction = 126,
                leadingEdge = true,
                trailingEdge = false,
            ),
        )
    }
}
