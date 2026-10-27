package dev.pam.nativeapp.render

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PamNavigationOrientationTest {
    @Test
    fun platformDefaultRestoresTheManifestOrientation() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            navigationRequestedOrientation(1, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            navigationRequestedOrientation(1, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
        )
    }

    @Test
    fun allFollowsTheUserRotationSetting() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            navigationRequestedOrientation(2, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            navigationRequestedOrientation(6, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        )
    }
}
