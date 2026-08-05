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
    fun measuredParentSizeWinsAfterTheFirstNativeLayout() {
        assertEquals(2_148, measuredParentExtent(2_148, 2_211))
        assertEquals(2_211, measuredParentExtent(0, 2_211))
    }

    @Test
    fun layoutOnlyDescendantUsesTheNativeParentsContentBox() {
        assertEquals(2_085, hostedContentExtent(2_148, 2_211, 63))
        assertEquals(0, hostedContentExtent(40, 40, 63))
    }
}
