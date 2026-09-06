package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamSafeAreaLayoutTest {
    @Test
    fun orientationGeometryChangeRequiresASecondInsetResolution() {
        assertEquals(
            true,
            safeAreaLayoutBoundsChanged(
                left = 0,
                top = 112,
                right = 1_080,
                bottom = 2_280,
                oldLeft = 112,
                oldTop = 0,
                oldRight = 2_280,
                oldBottom = 1_080,
            ),
        )
        assertEquals(
            false,
            safeAreaLayoutBoundsChanged(
                left = 0,
                top = 112,
                right = 1_080,
                bottom = 2_280,
                oldLeft = 0,
                oldTop = 112,
                oldRight = 1_080,
                oldBottom = 2_280,
            ),
        )
    }

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
    fun measuredColumnParentConstrainsLandscapeChildWidth() {
        assertEquals(
            126 to 0,
            measuredCrossAxisViewportReduction(
                mainAxisHorizontal = false,
                engineWidth = 2_280,
                measuredWidth = 2_154,
                engineHeight = 1_080,
                measuredHeight = 842,
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
    fun previousOrientationMeasurementIsStaleWhileRelayoutIsPending() {
        assertEquals(
            true,
            parentViewportMeasurementIsStale(
                measuredExtent = 954,
                layoutParamExtent = 2_337,
                layoutRequested = true,
            ),
        )
        assertEquals(
            false,
            parentViewportMeasurementIsStale(
                measuredExtent = 2_148,
                layoutParamExtent = 2_211,
                layoutRequested = false,
            ),
        )
        assertEquals(
            false,
            parentViewportMeasurementIsStale(
                measuredExtent = 2_337,
                layoutParamExtent = 2_337,
                layoutRequested = true,
            ),
        )
    }

    @Test
    fun layoutOnlyDescendantUsesTheNativeParentsContentBox() {
        assertEquals(2_085, hostedContentExtent(2_148, 2_211, 63))
        assertEquals(0, hostedContentExtent(40, 40, 63))
    }
}
