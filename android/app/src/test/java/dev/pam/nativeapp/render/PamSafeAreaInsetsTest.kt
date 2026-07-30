package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamSafeAreaInsetsTest {
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
}
