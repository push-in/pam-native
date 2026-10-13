package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardVisibilityTest {
    @Test fun framesAndModalHandoffEmitOnlyBinaryTransitions() {
        val events = mutableListOf<Boolean>()
        val visibility = KeyboardVisibility { events.add(it) }
        for (inset in 1..300) visibility.update(0, inset.toFloat())
        visibility.update(7, 300f)
        visibility.update(0, 0f)
        assertEquals(listOf(true), events)
        visibility.update(7, 0f)
        assertEquals(listOf(true, false), events)
    }

    @Test fun backgroundDropsSurfacesAndIgnoresLateFramesUntilResume() {
        val events = mutableListOf<Boolean>()
        val visibility = KeyboardVisibility { events.add(it) }
        visibility.update(9, 200f)
        visibility.setActive(false)
        visibility.update(9, 250f)
        visibility.setActive(true)
        visibility.update(0, 200f)
        visibility.update(0, 0f)
        assertEquals(listOf(true, false, true, false), events)
    }
}
