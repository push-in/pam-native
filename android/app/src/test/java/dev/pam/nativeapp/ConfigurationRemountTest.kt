package dev.pam.nativeapp

import android.content.pm.ActivityInfo
import dev.pam.nativeapp.PamActivity.Companion.requiresSurfaceRemount
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationRemountTest {
    @Test
    fun valuesBakedIntoMountedViewsRebuildThemInPlace() {
        for (change in listOf(
            ActivityInfo.CONFIG_FONT_SCALE,
            ActivityInfo.CONFIG_DENSITY,
            ActivityInfo.CONFIG_LOCALE,
            ActivityInfo.CONFIG_LAYOUT_DIRECTION,
            // A locale switch to an RTL language reports both.
            ActivityInfo.CONFIG_LOCALE or ActivityInfo.CONFIG_LAYOUT_DIRECTION,
            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_DENSITY,
        )) {
            assertTrue("change $change", requiresSurfaceRemount(change))
        }
    }

    @Test
    fun sizeKeyboardAndAppearanceChangesOnlyRelayout() {
        for (change in listOf(
            0,
            ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
                ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_SCREEN_LAYOUT,
            ActivityInfo.CONFIG_KEYBOARD or ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
            ActivityInfo.CONFIG_NAVIGATION,
            ActivityInfo.CONFIG_UI_MODE,
            ActivityInfo.CONFIG_MCC or ActivityInfo.CONFIG_MNC,
        )) {
            assertFalse("change $change", requiresSurfaceRemount(change))
        }
    }
}
