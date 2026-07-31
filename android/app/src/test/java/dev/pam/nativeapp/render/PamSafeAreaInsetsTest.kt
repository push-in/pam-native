package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamSafeAreaInsetsTest {
    private val window = SafeAreaBounds(0, 0, 1_080, 2_316)
    private val bars = SafeAreaInsets(left = 0, top = 94, right = 0, bottom = 135)

    @Test
    fun decor_fitted_content_does_not_apply_system_bars_twice() {
        assertEquals(
            SafeAreaInsets(0, 0, 0, 0),
            unconsumedSafeAreaInsets(
                raw = SafeAreaInsets(left = 0, top = 112, right = 0, bottom = 126),
                consumed = SafeAreaInsets(left = 0, top = 112, right = 0, bottom = 126),
            ),
        )
    }

    @Test
    fun edge_to_edge_content_keeps_the_full_safe_area() {
        val bars = SafeAreaInsets(left = 0, top = 112, right = 0, bottom = 126)

        assertEquals(
            bars,
            unconsumedSafeAreaInsets(
                raw = bars,
                consumed = SafeAreaInsets(0, 0, 0, 0),
            ),
        )
    }

    @Test
    fun mixed_layout_only_applies_each_unconsumed_edge() {
        assertEquals(
            SafeAreaInsets(left = 0, top = 112, right = 0, bottom = 0),
            unconsumedSafeAreaInsets(
                raw = SafeAreaInsets(left = 0, top = 112, right = 0, bottom = 126),
                consumed = SafeAreaInsets(left = 0, top = 0, right = 0, bottom = 126),
            ),
        )
    }

    @Test
    fun full_screen_view_receives_all_window_insets() {
        assertEquals(
            bars,
            safeAreaInsetsForBounds(
                raw = bars,
                window = window,
                target = window,
            ),
        )
    }

    @Test
    fun decor_fitted_view_does_not_receive_duplicate_insets() {
        assertEquals(
            SafeAreaInsets(0, 0, 0, 0),
            safeAreaInsetsForBounds(
                raw = bars,
                window = window,
                target = SafeAreaBounds(0, 94, 1_080, 2_181),
            ),
        )
    }

    @Test
    fun bottom_bar_only_receives_the_bottom_overlap() {
        assertEquals(
            SafeAreaInsets(left = 0, top = 0, right = 0, bottom = 135),
            safeAreaInsetsForBounds(
                raw = bars,
                window = window,
                target = SafeAreaBounds(0, 1_945, 1_080, 2_316),
            ),
        )
    }

    @Test
    fun landscape_cutout_only_affects_the_overlapping_side() {
        assertEquals(
            SafeAreaInsets(left = 88, top = 0, right = 0, bottom = 48),
            safeAreaInsetsForBounds(
                raw = SafeAreaInsets(left = 88, top = 0, right = 0, bottom = 48),
                window = SafeAreaBounds(0, 0, 2_316, 1_080),
                target = SafeAreaBounds(0, 0, 2_316, 1_080),
            ),
        )
    }
}
