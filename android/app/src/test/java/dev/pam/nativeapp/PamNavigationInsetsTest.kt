package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class PamNavigationInsetsTest {
    @Test fun missingThreeButtonBottomUses48DpAtDeviceDensity() {
        assertEquals(144, navigationBottomInset(0, 0, 0, true, 144))
    }
    @Test fun absentOrHiddenBarNeverGetsSyntheticPadding() {
        assertEquals(0, navigationBottomInset(0, 0, 0, false, 144))
    }
    @Test fun systemMeasurementsTakePriorityOverFallback() {
        assertEquals(126, navigationBottomInset(0, 126, 0, true, 144))
        assertEquals(126, navigationBottomInset(0, 0, 126, true, 144))
        assertEquals(24, navigationBottomInset(24, 0, 0, false, 144))
        assertEquals(135, navigationBottomInset(135, 126, 0, true, 144))
    }
}
