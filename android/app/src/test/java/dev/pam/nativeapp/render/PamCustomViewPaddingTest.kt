package dev.pam.nativeapp.render

import dev.pam.nativeapp.protocol.NodeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamCustomViewPaddingTest {
    @Test
    fun engineFramesDoNotApplyNativeHostPaddingTwice() {
        assertEquals(0, engineFrameMargin(21, 21))
        assertEquals(42, engineFrameMargin(63, 21))
        assertEquals(63, engineFrameMargin(63, 0))
        // An absolute child may intentionally start before the padded origin.
        assertEquals(-21, engineFrameMargin(0, 21))
    }

    @Test
    fun customNativeViewGroupsOwnTheirAuthoredContentInsets() {
        assertTrue(usesNativeViewGroupPadding(NodeKind.CUSTOM_VIEW))
        assertFalse(usesNativeViewGroupPadding(NodeKind.VIEW))
        assertFalse(usesNativeViewGroupPadding(NodeKind.SCREEN))
    }
}
