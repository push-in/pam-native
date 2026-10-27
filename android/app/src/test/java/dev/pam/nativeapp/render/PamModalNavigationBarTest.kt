package dev.pam.nativeapp.render

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PamModalNavigationBarTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun translucent_modal_is_transparent_scrimmed_with_icons_following_the_light_theme() {
        assertEquals(
            ModalNavigationBarStyle(Color.TRANSPARENT, contrastEnforced = true, lightAppearance = true),
            modalNavigationBarStyle(sdkInt = 31, translucent = true, lightAppearance = true, windowBackground = white),
        )
    }

    @Test
    fun bottom_sheet_bar_is_transparent_without_a_system_scrim_like_the_gorhom_root() {
        assertEquals(
            ModalNavigationBarStyle(Color.TRANSPARENT, contrastEnforced = false, lightAppearance = true),
            modalNavigationBarStyle(31, translucent = true, lightAppearance = true, windowBackground = white, sheet = true),
        )
        assertEquals(
            ModalNavigationBarStyle(Color.TRANSPARENT, contrastEnforced = false, lightAppearance = false),
            modalNavigationBarStyle(34, translucent = true, lightAppearance = false, windowBackground = black, sheet = true),
        )
    }

    @Test
    fun translucent_modal_keeps_light_icons_in_the_dark_theme() {
        assertEquals(
            ModalNavigationBarStyle(Color.TRANSPARENT, contrastEnforced = true, lightAppearance = false),
            modalNavigationBarStyle(sdkInt = 34, translucent = true, lightAppearance = false, windowBackground = black),
        )
    }

    @Test
    fun translucent_modal_uses_react_native_colours_before_android_10() {
        assertEquals(
            MODAL_LIGHT_NAVIGATION_BAR,
            modalNavigationBarStyle(28, translucent = true, lightAppearance = true, windowBackground = white).color,
        )
        assertEquals(
            MODAL_DARK_NAVIGATION_BAR,
            modalNavigationBarStyle(28, translucent = true, lightAppearance = false, windowBackground = black).color,
        )
    }

    @Test
    fun fitted_modal_paints_the_app_window_background_not_the_dialog_black() {
        assertEquals(
            ModalNavigationBarStyle(white, contrastEnforced = false, lightAppearance = true),
            modalNavigationBarStyle(31, translucent = false, lightAppearance = true, windowBackground = white),
        )
        assertEquals(
            ModalNavigationBarStyle(black, contrastEnforced = false, lightAppearance = false),
            modalNavigationBarStyle(31, translucent = false, lightAppearance = false, windowBackground = black),
        )
    }

    @Test
    fun light_icons_are_unavailable_before_android_8() {
        val style = modalNavigationBarStyle(25, translucent = false, lightAppearance = true, windowBackground = white)
        assertEquals(false, style.lightAppearance)
        assertEquals(MODAL_DARK_NAVIGATION_BAR, style.color)
    }

    @Test
    fun edge_to_edge_modal_buttons_follow_the_authored_status_bar_style() {
        // Always-dark editor (light-content) in the light theme: light buttons.
        assertEquals(false, modalNavigationBarLightAppearance(31, edgeToEdge = true, authoredDarkIcons = false, themeDarkIcons = true))
        // Light screen (dark-content) in the dark theme: dark buttons.
        assertEquals(true, modalNavigationBarLightAppearance(34, edgeToEdge = true, authoredDarkIcons = true, themeDarkIcons = false))
        // No StatusBar style: the theme decides.
        assertEquals(true, modalNavigationBarLightAppearance(31, edgeToEdge = true, authoredDarkIcons = null, themeDarkIcons = true))
    }

    @Test
    fun opaque_or_pre_q_modal_buttons_keep_the_theme_appearance() {
        assertEquals(true, modalNavigationBarLightAppearance(31, edgeToEdge = false, authoredDarkIcons = false, themeDarkIcons = true))
        assertEquals(true, modalNavigationBarLightAppearance(28, edgeToEdge = true, authoredDarkIcons = false, themeDarkIcons = true))
    }
}
