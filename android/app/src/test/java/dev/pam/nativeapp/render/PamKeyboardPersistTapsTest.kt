package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamKeyboardPersistTapsTest {
    @Test fun wireValuesMatchThePhpEnumAndUnknownIsAbsent() {
        assertEquals(1, KeyboardPersistTaps.NEVER)
        assertEquals(2, KeyboardPersistTaps.ALWAYS)
        assertEquals(3, KeyboardPersistTaps.HANDLED)
        assertEquals(KeyboardPersistTaps.NONE, KeyboardPersistTaps.normalize(0))
        assertEquals(KeyboardPersistTaps.NONE, KeyboardPersistTaps.normalize(9))
        assertEquals(KeyboardPersistTaps.HANDLED, KeyboardPersistTaps.normalize(3))
    }

    @Test fun onlyNeverAndHandledInstallATracker() {
        assertFalse(KeyboardPersistTaps.tracks(KeyboardPersistTaps.NONE))
        assertFalse(KeyboardPersistTaps.tracks(KeyboardPersistTaps.ALWAYS))
        assertTrue(KeyboardPersistTaps.tracks(KeyboardPersistTaps.NEVER))
        assertTrue(KeyboardPersistTaps.tracks(KeyboardPersistTaps.HANDLED))
    }

    @Test fun decidesLikeReactNative() {
        val k = KeyboardPersistTaps
        // Never: any tap dismisses and is consumed, even on a pressable.
        assertEquals(k.DISMISS_AND_CONSUME, k.decide(k.NEVER, true, true, false, true))
        assertEquals(k.DISMISS_AND_CONSUME, k.decide(k.NEVER, true, true, false, false))
        // Handled: a handled tap keeps the keyboard; an unhandled one dismisses it.
        assertEquals(k.PASS, k.decide(k.HANDLED, true, true, false, true))
        assertEquals(k.DISMISS, k.decide(k.HANDLED, true, true, false, false))
        // Always and absent never dismiss.
        assertEquals(k.PASS, k.decide(k.ALWAYS, true, true, false, false))
        assertEquals(k.PASS, k.decide(k.NONE, true, true, false, false))
        // No keyboard, a drag, or a tap on a text input: nothing happens.
        assertEquals(k.PASS, k.decide(k.NEVER, false, true, false, false))
        assertEquals(k.PASS, k.decide(k.NEVER, true, false, false, false))
        assertEquals(k.PASS, k.decide(k.NEVER, true, true, true, true))
    }
}
