package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamStatusBarAppearanceTest {
    private val lightStatusBarMask = 8

    @Test
    fun unattachedLightThemeDefaultsToDarkIcons() {
        assertTrue(
            useDarkStatusBarIcons(
                systemBarsAppearance = null,
                darkTheme = false,
                lightStatusBarMask = lightStatusBarMask,
            ),
        )
    }

    @Test
    fun unattachedDarkThemeDefaultsToLightIcons() {
        assertFalse(
            useDarkStatusBarIcons(
                systemBarsAppearance = null,
                darkTheme = true,
                lightStatusBarMask = lightStatusBarMask,
            ),
        )
    }

    @Test
    fun attachedControllerAppearanceWinsOverThemeFallback() {
        assertTrue(
            useDarkStatusBarIcons(
                systemBarsAppearance = lightStatusBarMask,
                darkTheme = true,
                lightStatusBarMask = lightStatusBarMask,
            ),
        )
        assertFalse(
            useDarkStatusBarIcons(
                systemBarsAppearance = 0,
                darkTheme = false,
                lightStatusBarMask = lightStatusBarMask,
            ),
        )
    }
}
