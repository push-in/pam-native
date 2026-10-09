package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamModalBackdropTest {
    @Test
    fun flattenedContentCountsEverySibling() {
        // A backdrop Pressable (top) and the option panel (bottom) arrive as
        // siblings once their layout-only wrapper is flattened.
        val children = listOf(intArrayOf(0, 112, 1080, 765), intArrayOf(0, 765, 1080, 2280))
        assertFalse(isPointOutsideModalChildren(540f, 1393f, children))
        assertFalse(isPointOutsideModalChildren(540f, 400f, children))
        assertTrue(isPointOutsideModalChildren(540f, 50f, children))
        assertTrue(isPointOutsideModalChildren(540f, 50f, emptyList()))
    }
}
