package dev.pam.nativeapp.render

import org.junit.Assert.*
import org.junit.Test

class PamGestureDriversTest {
    @Test fun pressBackgroundInterpolatesLightDarkAndAlphaWithoutLosingChannels() {
        for ((base, pressed) in listOf(0xFFFFFFFF.toInt() to 0xFFE5E7EB.toInt(),
            0xFF111827.toInt() to 0xFF374151.toInt(), 0 to 0x80112233.toInt())) {
            assertEquals(base, pressedBackgroundColor(base, pressed, 0f))
            assertEquals(pressed, pressedBackgroundColor(base, pressed, 1f))
            assertEquals(base, pressedBackgroundColor(base, pressed, -1f))
            assertEquals(pressed, pressedBackgroundColor(base, pressed, 2f))
        }
        assertEquals(0xFF7F7F7F.toInt(), pressedBackgroundColor(0xFF000000.toInt(), -1, .5f))
    }

    @Test fun keyboardTracksAreBoundedAndFollowShowHideAndInterruptedAnimations() {
        val tracks = PamKeyboardTrack.parse("opacity|1|0\ntranslateY|0|-120")!!
        assertEquals(.5, tracks[0].value(.5), 0.0)
        assertEquals(-60.0, tracks[1].value(.5), 0.0)
        assertEquals(-120.0, tracks[1].value(2.0), 0.0)
        assertEquals(.25, keyboardAnimationProgress(0.0, 1.0, .25), 0.0)
        assertEquals(.75, keyboardAnimationProgress(1.0, 0.0, .25), 0.0)
        assertEquals(.3, keyboardAnimationProgress(.6, 0.0, .5), .00001)
        for (invalid in listOf("opacity|0|NaN", "scale|1|2", "opacity|0|2", "opacity|0|1\nopacity|1|0", "")) {
            assertNull(PamKeyboardTrack.parse(invalid))
        }
    }
}
