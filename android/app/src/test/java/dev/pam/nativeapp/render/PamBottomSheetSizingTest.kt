package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** Galaxy S10 at 1080x2280: 63 px status bar, 126 px three-button navigation bar. */
class PamBottomSheetSizingTest {
    @Test
    fun edgeToEdgeSheetResolvesAgainstTheWholeWindowMinusTheTopInsetBeforeLayout() {
        assertEquals(
            2_217,
            sheetAvailableHeight(
                laidOutContainerHeight = null,
                windowHeight = 2_280,
                topInset = 63,
                bottomInset = 126,
                edgeToEdge = true,
            ),
        )
    }

    @Test
    fun laidOutEdgeToEdgeContainerGivesTheSameBaseAsThePreLayoutEstimate() {
        assertEquals(
            sheetAvailableHeight(null, 2_280, 63, 126, edgeToEdge = true),
            sheetAvailableHeight(2_280, 2_280, 63, 126, edgeToEdge = true),
        )
    }

    @Test
    fun baseSheetRestsOnTheNavigationBarWithoutCountingTheStatusBarTwice() {
        // The fitted window already sits between the system bars.
        assertEquals(2_091, sheetAvailableHeight(null, 2_280, 63, 126, edgeToEdge = false))
        assertEquals(2_091, sheetAvailableHeight(2_091, 2_280, 63, 126, edgeToEdge = false))
    }

    /**
     * React Native's @gorhom sheet lives in the edge-to-edge root: its
     * backdrop covers the navigation bar and a percentage detent resolves
     * against the whole window minus the top inset. A BottomSheet window is
     * therefore always edge to edge, whatever its translucency props; other
     * presentations keep fitting the system bars unless translucent.
     */
    @Test
    fun bottomSheetWindowIsAlwaysEdgeToEdgeLikeTheGorhomSheet() {
        assertEquals(true, modalWindowEdgeToEdge(presentation = 3, statusBarTranslucent = false, navigationBarTranslucent = false))
        assertEquals(false, modalWindowEdgeToEdge(presentation = 1, statusBarTranslucent = false, navigationBarTranslucent = false))
        assertEquals(false, modalWindowEdgeToEdge(presentation = 2, statusBarTranslucent = false, navigationBarTranslucent = false))
        assertEquals(true, modalWindowEdgeToEdge(presentation = 1, statusBarTranslucent = false, navigationBarTranslucent = true))
        // S10, 68 % detent: the sheet top lands where @gorhom puts it
        // (2280 - 0.68 x (2280 - 63)), not 40 px higher on the navigation bar.
        val base = sheetAvailableHeight(null, 2_280, 63, 126, modalWindowEdgeToEdge(3, false, false))
        assertEquals(773, 2_280 - (base * 0.68f).toInt())
    }

    @Test
    fun degenerateGeometryKeepsAPositiveBase() {
        assertEquals(1, sheetAvailableHeight(null, 0, 63, 126, edgeToEdge = false))
    }
}
