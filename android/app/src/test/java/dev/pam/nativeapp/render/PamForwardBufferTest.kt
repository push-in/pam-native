package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `preloadSeconds` → ExoPlayer DefaultLoadControl targets (forward buffer). */
class PamForwardBufferTest {
    @Test
    fun zeroKeepsThePlatformMediaPlayer() {
        assertNull(pamForwardBuffer(0))
        assertNull(pamForwardBuffer(-3))
    }

    @Test
    fun forwardBufferKeepsThatMuchMediaAheadAndStartsWithTheExoDefaults() {
        assertEquals(PamForwardBuffer(12_000, 12_000, 2_500, 5_000), pamForwardBuffer(12))
    }

    @Test
    fun shortBuffersCapThePlaybackThresholdsAtTheTarget() {
        // DefaultLoadControl rejects bufferForPlayback > minBuffer.
        assertEquals(PamForwardBuffer(2_000, 2_000, 2_000, 2_000), pamForwardBuffer(2))
    }

    @Test
    fun theTargetIsBoundedLikeThePhpSetter() {
        assertEquals(300_000, pamForwardBuffer(10_000)!!.maxBufferMs)
    }

    @Test
    fun startBufferAloneKeepsTheExoTargetsAndStartsAfterThatMuchMedia() {
        // RN stories: bufferForPlaybackMs 350, rebuffer ~2x.
        assertEquals(PamForwardBuffer(50_000, 50_000, 350, 700), pamForwardBuffer(0, 350))
    }

    @Test
    fun startBufferWithForwardBufferIsCappedByTheTarget() {
        assertEquals(PamForwardBuffer(8_000, 8_000, 500, 1_000), pamForwardBuffer(8, 500))
        assertEquals(PamForwardBuffer(1_000, 1_000, 1_000, 1_000), pamForwardBuffer(1, 4_000))
    }

    @Test
    fun rebufferThresholdNeverExceedsTheExoDefault() {
        assertEquals(5_000, pamForwardBuffer(0, 9_000)!!.bufferForPlaybackAfterRebufferMs)
        assertNull(pamForwardBuffer(0, 0))
        assertNull(pamForwardBuffer(0, -10))
    }
}
