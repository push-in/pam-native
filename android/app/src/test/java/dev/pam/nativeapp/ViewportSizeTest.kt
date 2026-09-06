package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportSizeTest {
    @Test
    fun laidOutViewportWinsOverStaleWindowMetricsAfterRotation() {
        assertEquals(
            1_080 to 2_280,
            resolvedViewportSize(
                laidOutWidth = 1_080,
                laidOutHeight = 2_280,
                windowWidth = 2_280,
                windowHeight = 1_080,
            ),
        )
    }

    @Test
    fun windowMetricsBootstrapTheViewportBeforeFirstLayout() {
        assertEquals(
            1_080 to 2_280,
            resolvedViewportSize(
                laidOutWidth = 0,
                laidOutHeight = 0,
                windowWidth = 1_080,
                windowHeight = 2_280,
            ),
        )
    }
}
