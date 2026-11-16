package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PamAccessibilityPolicyTest {
    @Test
    fun adjustable_nodes_with_a_handler_take_talkback_slider_actions() {
        assertEquals(0x1000, ACCESSIBILITY_SCROLL_FORWARD)
        assertEquals(0x2000, ACCESSIBILITY_SCROLL_BACKWARD)
        assertTrue(adjustsWithAccessibilityActions(ACCESSIBILITY_ROLE_ADJUSTABLE, true))
        assertEquals(
            "increment",
            adjustableAccessibilityActionName(ACCESSIBILITY_ROLE_ADJUSTABLE, true, ACCESSIBILITY_SCROLL_FORWARD),
        )
        assertEquals(
            "decrement",
            adjustableAccessibilityActionName(ACCESSIBILITY_ROLE_ADJUSTABLE, true, ACCESSIBILITY_SCROLL_BACKWARD),
        )
    }

    @Test
    fun other_roles_or_nodes_without_a_handler_keep_the_platform_scroll_actions() {
        assertFalse(adjustsWithAccessibilityActions(ACCESSIBILITY_ROLE_ADJUSTABLE, false))
        assertFalse(adjustsWithAccessibilityActions(1, true))
        assertNull(adjustableAccessibilityActionName(ACCESSIBILITY_ROLE_ADJUSTABLE, false, ACCESSIBILITY_SCROLL_FORWARD))
        assertNull(adjustableAccessibilityActionName(32, true, ACCESSIBILITY_SCROLL_FORWARD))
        assertNull(adjustableAccessibilityActionName(ACCESSIBILITY_ROLE_ADJUSTABLE, true, 0x10))
    }

    @Test
    fun checked_state_none_falls_back_to_the_checked_prop() {
        assertEquals(1, announcedCheckedState(0L, checked = false))
        assertEquals(2, announcedCheckedState(0L, checked = true))
        assertEquals(1, announcedCheckedState(null, checked = false))
        assertEquals(2, announcedCheckedState(null, checked = true))
        assertEquals(1, announcedCheckedState(1L, checked = true))
        assertEquals(2, announcedCheckedState(2L, checked = false))
        assertEquals(3, announcedCheckedState(3L, checked = false))
    }
}
