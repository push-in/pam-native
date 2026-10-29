package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamKeyboardFollowTest {
    @Test fun aClosingKeyboardReachesZeroOnlyWhenItsAnimationEnds() {
        assertEquals(640, imeAnimationFrameInset(640, 991))
        assertEquals(1, imeAnimationFrameInset(0, 12))
        assertEquals(0, imeAnimationFrameInset(0, 0))
        assertEquals(0, imeAnimationFrameInset(-3, 0))
    }

    @Test fun observersSeeAClosingKeyboardHiddenAtOnceAndAnOpeningOneOnceItIsUp() {
        assertEquals(0, publishedImeDuringAnimation(target = 0, previous = 991))
        assertEquals(0, publishedImeDuringAnimation(target = 991, previous = 0))
        assertEquals(991, publishedImeDuringAnimation(target = 1020, previous = 991))
    }

    @Test fun thePinnedLastRowEndsFlushWithTheResizedViewport() {
        // 1500 px viewport, 12 px padding each side, a 220 px newest row.
        val offset = pinnedEndOffset(1500, 24, 220)
        assertEquals(1500 - 12, 12 + offset + 220)
        // A row taller than the viewport keeps its bottom on the edge too.
        assertEquals(-724, pinnedEndOffset(500, 24, 1200))
    }
}
