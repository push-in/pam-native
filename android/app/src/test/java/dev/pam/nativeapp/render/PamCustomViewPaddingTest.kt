package dev.pam.nativeapp.render

import dev.pam.nativeapp.protocol.NodeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamCustomViewPaddingTest {
    @Test
    fun customNativeViewGroupsOwnTheirAuthoredContentInsets() {
        assertTrue(usesNativeViewGroupPadding(NodeKind.CUSTOM_VIEW))
        assertFalse(usesNativeViewGroupPadding(NodeKind.VIEW))
        assertFalse(usesNativeViewGroupPadding(NodeKind.SCREEN))
    }
}
