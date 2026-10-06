package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackLifecycleTest {
    private fun playing() = MediaPlaybackLifecycle().apply {
        visibility(attached = true, visible = true)
        autoPlay(true)
    }

    @Test fun latePrepareAndQueuedStartCannotPlayAfterHostPause() {
        val lifecycle = playing()
        // Preparation queued work while foregrounded; playback checks current eligibility.
        val queuedPrepare = { lifecycle.mayPlay }
        lifecycle.hostActive(false)
        assertFalse(queuedPrepare())
        lifecycle.hostActive(true)
        assertTrue(queuedPrepare())
    }

    @Test fun manualPauseSurvivesBackgroundVisibilityAndRepeatedProps() {
        val lifecycle = playing()
        lifecycle.request(false)
        lifecycle.hostActive(false)
        lifecycle.visibility(true, false)
        lifecycle.autoPlay(true)
        lifecycle.hostActive(true)
        lifecycle.visibility(true, true)
        assertFalse(lifecycle.mayPlay)
        lifecycle.request(true)
        assertTrue(lifecycle.mayPlay)
    }

    @Test fun manualPlayWhileHiddenWaitsForVisibleForeground() {
        val lifecycle = playing()
        lifecycle.hostActive(false)
        lifecycle.visibility(true, false)
        lifecycle.request(true)
        assertFalse(lifecycle.mayPlay)
        lifecycle.hostActive(true)
        assertFalse(lifecycle.mayPlay)
        lifecycle.visibility(true, true)
        assertTrue(lifecycle.mayPlay)
    }

    @Test fun detachedPreparedAndLoopingPlayersCannotStart() {
        val lifecycle = playing()
        lifecycle.visibility(false, false)
        lifecycle.completed(looping = true)
        lifecycle.autoPlay(true)
        assertFalse(lifecycle.mayPlay)
        lifecycle.visibility(true, false)
        assertFalse(lifecycle.mayPlay)
        lifecycle.visibility(true, true)
        assertTrue(lifecycle.mayPlay)
    }

    @Test fun changingAutoplayToFalseInBackgroundCancelsResume() {
        val lifecycle = playing()
        lifecycle.hostActive(false)
        lifecycle.autoPlay(false)
        lifecycle.hostActive(true)
        assertFalse(lifecycle.mayPlay)
    }

    @Test fun completedVideoStaysStoppedAcrossHostResume() {
        val lifecycle = playing()
        lifecycle.completed(looping = false)
        lifecycle.hostActive(false)
        lifecycle.hostActive(true)
        assertFalse(lifecycle.mayPlay)
        lifecycle.request(true)
        assertTrue(lifecycle.mayPlay)
    }
}
