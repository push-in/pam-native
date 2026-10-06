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

    @Test
    fun degenerateGeometryKeepsAPositiveBase() {
        assertEquals(1, sheetAvailableHeight(null, 0, 63, 126, edgeToEdge = false))
    }
}
