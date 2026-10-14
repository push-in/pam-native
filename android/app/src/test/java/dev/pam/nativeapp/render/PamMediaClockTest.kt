package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamMediaClockTest {
    @Test fun formatsEveryMediaClockFormat() {
        assertEquals("0:42", PamMediaClock.format(PamMediaClock.ELAPSED, 42.9, 120.0))
        assertEquals("1:18", PamMediaClock.format(PamMediaClock.REMAINING, 42.0, 120.0))
        assertEquals("1:19", PamMediaClock.format(PamMediaClock.REMAINING, 41.5, 120.0))
        assertEquals("0:42 / 2:00", PamMediaClock.format(PamMediaClock.ELAPSED_AND_DURATION, 42.2, 120.0))
        assertEquals("2:00", PamMediaClock.format(PamMediaClock.DURATION, 0.0, 120.0))
        assertEquals("5", PamMediaClock.format(PamMediaClock.COUNTDOWN_SECONDS, 10.2, 15.0))
        assertEquals("0", PamMediaClock.format(PamMediaClock.COUNTDOWN_SECONDS, 15.0, 15.0))
        assertEquals("1:01:05", PamMediaClock.format(PamMediaClock.ELAPSED, 3_665.0, 4_000.0))
        assertEquals("0:00", PamMediaClock.format(PamMediaClock.ELAPSED, Double.NaN, Double.NaN))
        assertEquals("0:00", PamMediaClock.format(PamMediaClock.REMAINING, 50.0, 0.0))
    }

    @Test fun progressFractionIsClampedAndZeroWithoutDuration() {
        assertEquals(0.25f, PamMediaClock.fraction(30.0, 120.0), 1e-6f)
        assertEquals(1f, PamMediaClock.fraction(200.0, 120.0), 1e-6f)
        assertEquals(0f, PamMediaClock.fraction(10.0, 0.0), 1e-6f)
        assertEquals(0f, PamMediaClock.fraction(10.0, Double.POSITIVE_INFINITY), 1e-6f)
    }

    @Test fun routeFocusTrackerIsEdgeTriggered() {
        val tracker = RouteFocusTracker()
        assertNull(tracker.update(false))
        assertEquals(true, tracker.update(true))
        assertNull(tracker.update(true))
        assertEquals(false, tracker.update(false))
    }
}
