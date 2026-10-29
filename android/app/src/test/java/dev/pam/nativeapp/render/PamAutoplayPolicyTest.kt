package dev.pam.nativeapp.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamAutoplayPolicyTest {
    private val list = Any()

    private fun player(
        fraction: Float,
        top: Int,
        active: Boolean = false,
        idle: Boolean = true,
        scope: Any? = "feed" to list,
        threshold: Float = 0.5f,
    ) = PamAutoplayCandidate(fraction, threshold, idle, scope, top, active)

    @Test fun startsOnlyAtRestWithTheThresholdOnScreen() {
        assertTrue(PamAutoplayPolicy.choose(listOf(player(0.5f, 0))).single())
        assertFalse(PamAutoplayPolicy.choose(listOf(player(0.49f, 0))).single())
        assertFalse("not while the list moves", PamAutoplayPolicy.choose(listOf(player(1f, 0, idle = false))).single())
    }

    @Test fun aPlayingVideoKeepsPlayingWhileTheListMovesUntilBelowItsFloor() {
        assertEquals(0.3f, PamAutoplayPolicy.floor(0.5f), 1e-4f)
        assertTrue(PamAutoplayPolicy.choose(listOf(player(0.35f, 0, active = true, idle = false))).single())
        assertFalse(PamAutoplayPolicy.choose(listOf(player(0.29f, 0, active = true, idle = false))).single())
        assertFalse("off screen", PamAutoplayPolicy.choose(listOf(player(0f, 0, active = true, threshold = 0.01f))).single())
    }

    @Test fun oneVideoPerGroupTheTopmostEligible() {
        val chosen = PamAutoplayPolicy.choose(listOf(player(1f, 900), player(0.8f, 100), player(0.2f, 50)))
        assertArrayEquals(booleanArrayOf(false, true, false), chosen)
    }

    @Test fun thePlayingVideoStaysWhileItMeetsItsThreshold() {
        val chosen = PamAutoplayPolicy.choose(listOf(player(0.6f, 900, active = true), player(1f, 100)))
        assertArrayEquals(booleanArrayOf(true, false), chosen)
    }

    @Test fun atRestABetterVisibleVideoTakesOverFromOneInItsHysteresisBand() {
        // React Native: the first viewable video plays; the old one is no longer viewable.
        val chosen = PamAutoplayPolicy.choose(listOf(player(0.4f, 0, active = true), player(1f, 600)))
        assertArrayEquals(booleanArrayOf(false, true), chosen)
        // Nothing else eligible: the old one keeps playing within its band.
        assertTrue(PamAutoplayPolicy.choose(listOf(player(0.4f, 0, active = true), player(0.2f, 600)))[0])
    }

    @Test fun whileMovingNoGroupSwitchesToANewVideo() {
        val chosen = PamAutoplayPolicy.choose(
            listOf(player(0.25f, 0, active = true, idle = false), player(1f, 600, idle = false)),
        )
        assertArrayEquals(booleanArrayOf(false, false), chosen)
    }

    @Test fun groupsAreScopedPerListAndUngroupedPlayersDecideAlone() {
        val other = "feed" to Any()
        val chosen = PamAutoplayPolicy.choose(
            listOf(
                player(1f, 0),
                player(1f, 10, scope = other),
                player(1f, 20, scope = null),
                player(0.6f, 30, scope = null),
            ),
        )
        assertArrayEquals(booleanArrayOf(true, true, true, true), chosen)
    }

    @Test fun aFullyVisibleThresholdToleratesRounding() {
        assertTrue(PamAutoplayPolicy.choose(listOf(player(0.9995f, 0, threshold = 1f))).single())
    }
}
