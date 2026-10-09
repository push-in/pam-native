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
}
