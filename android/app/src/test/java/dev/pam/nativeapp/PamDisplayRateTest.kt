package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class PamDisplayRateTest {
    @Test
    fun adaptivePanelIdlingAt60ReportsItsPeakAtTheSameResolution() {
        val modes = listOf(
            DisplayRateMode(1080, 2340, 60f),
            DisplayRateMode(1080, 2340, 120f),
            DisplayRateMode(1440, 3120, 144f),
        )
        assertEquals(120f, peakRefreshRate(1080, 2340, 60f, modes))
    }

    @Test
    fun fixedPanelsAndUnknownModesKeepTheCurrentRate() {
        assertEquals(60f, peakRefreshRate(1080, 2280, 60f, listOf(DisplayRateMode(1080, 2280, 60f))))
        assertEquals(90f, peakRefreshRate(720, 1600, 90f, emptyList()))
        assertEquals(1f, peakRefreshRate(720, 1600, 0f, emptyList()))
    }
}
