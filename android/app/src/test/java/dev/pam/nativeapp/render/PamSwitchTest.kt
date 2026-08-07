package dev.pam.nativeapp.render

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

class PamSwitchTest {
    @Test
    fun intrinsicSwitchUsesReactNativeCompatibleExtent() {
        assertEquals(
            122,
            resolvePamSwitchMeasuredExtent(View.MeasureSpec.UNSPECIFIED, 0, 122),
        )
        assertEquals(
            71,
            resolvePamSwitchMeasuredExtent(View.MeasureSpec.UNSPECIFIED, 0, 71),
        )
    }

    @Test
    fun authoredExactSwitchExtentWins() {
        assertEquals(
            160,
            resolvePamSwitchMeasuredExtent(View.MeasureSpec.EXACTLY, 160, 122),
        )
    }

    @Test
    fun constrainedIntrinsicSwitchDoesNotOverflow() {
        assertEquals(
            96,
            resolvePamSwitchMeasuredExtent(View.MeasureSpec.AT_MOST, 96, 122),
        )
    }
}
