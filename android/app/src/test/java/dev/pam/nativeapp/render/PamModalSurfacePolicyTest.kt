package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamModalSurfacePolicyTest {
    @Test
    fun dialogs_fit_the_system_bars_up_to_android_14() {
        for (sdk in 26..34) {
            assertEquals(SURFACE_POLICY_SYSTEM_WINDOWS, modalWindowSurfacePolicy(sdk, 36))
        }
    }

    @Test
    fun enforced_edge_to_edge_makes_every_dialog_reach_the_system_bars() {
        assertEquals(SURFACE_POLICY_EDGE_TO_EDGE_WINDOWS, modalWindowSurfacePolicy(35, 35))
        assertEquals(SURFACE_POLICY_EDGE_TO_EDGE_WINDOWS, modalWindowSurfacePolicy(36, 36))
    }

    @Test
    fun apps_targeting_android_14_keep_fitted_dialogs_on_newer_releases() {
        assertEquals(SURFACE_POLICY_SYSTEM_WINDOWS, modalWindowSurfacePolicy(36, 34))
    }
}
