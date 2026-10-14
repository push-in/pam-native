package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamPageTrackerTest {
    @Test
    fun reportsOnlyActualPageChanges() {
        val tracker = PamPageTracker()
        tracker.reset(0)
        repeat(30) { assertNull(tracker.advance(0)) }
        assertEquals(0, tracker.advance(1))
        repeat(30) { assertNull(tracker.advance(1)) }
        assertEquals(1, tracker.advance(0))
    }

    @Test
    fun firstObservationWithoutResetIsSilent() {
        val tracker = PamPageTracker()
        assertNull(tracker.advance(2))
        assertEquals(2, tracker.advance(3))
    }

    @Test
    fun negativeOverscrollPagesClampToZero() {
        val tracker = PamPageTracker()
        tracker.reset(0)
        assertNull(tracker.advance(-1))
    }
}
