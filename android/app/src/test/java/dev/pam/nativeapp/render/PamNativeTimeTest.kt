package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamNativeTimeTest {
    private val start = 1_763_300_000_000L

    @Test fun formatsEveryClockFormat() {
        assertEquals("0:42", PamClockFormat.format(PamClockFormat.MINUTES_SECONDS, 42))
        assertEquals("1:01:05", PamClockFormat.format(PamClockFormat.MINUTES_SECONDS, 3_665))
        assertEquals("00:42", PamClockFormat.format(PamClockFormat.PADDED_MINUTES_SECONDS, 42))
        assertEquals("75:03", PamClockFormat.format(PamClockFormat.PADDED_MINUTES_SECONDS, 4_503))
        assertEquals("0:00:42", PamClockFormat.format(PamClockFormat.HOURS_MINUTES_SECONDS, 42))
        assertEquals("42", PamClockFormat.format(PamClockFormat.SECONDS, 42))
        assertEquals("0:00", PamClockFormat.format(PamClockFormat.MINUTES_SECONDS, -5))
    }

    @Test fun clockCountsUpFromItsStartAndOffset() {
        val clock = PamClockSpec(PamTimeSource(start, 2_000, true), null, null, PamClockFormat.PADDED_MINUTES_SECONDS)
        assertEquals("00:02", clock.label(start))
        assertEquals("00:44", clock.label(start + 42_999))
        assertEquals(1L, clock.nextChangeIn(start + 42_999))
        assertEquals(1_000L, clock.nextChangeIn(start + 43_000))
        // A start a little in the future (clock skew) stands at the offset until then.
        assertEquals("00:02", clock.label(start - 500))
        assertEquals(500L, clock.nextChangeIn(start - 500))
    }

    @Test fun pausedClockStandsAtItsOffsetAndNeverTicks() {
        val clock = PamClockSpec(PamTimeSource(start, 61_000, false), null, null, PamClockFormat.MINUTES_SECONDS)
        assertEquals("1:01", clock.label(start + 99_000))
        assertEquals(-1L, clock.nextChangeIn(start + 99_000))
        val unstarted = PamClockSpec(PamTimeSource(null, 0, true), null, null, PamClockFormat.MINUTES_SECONDS)
        assertEquals("0:00", unstarted.label(start))
        assertEquals(-1L, unstarted.nextChangeIn(start))
    }

    @Test fun countdownsRoundUpAndStopAtZero() {
        val deadline = PamClockSpec(PamTimeSource(null, 0, true), null, start + 5_000, PamClockFormat.SECONDS)
        assertEquals("5", deadline.label(start))
        assertEquals("5", deadline.label(start + 1))
        assertEquals(999L, deadline.nextChangeIn(start + 1))
        assertEquals("1", deadline.label(start + 4_999))
        assertEquals("0", deadline.label(start + 5_000))
        assertEquals(-1L, deadline.nextChangeIn(start + 5_000))
        val timer = PamClockSpec(PamTimeSource(start, 10_000, true), 30_000, null, PamClockFormat.MINUTES_SECONDS)
        assertEquals("0:20", timer.label(start))
        assertEquals("0:15", timer.label(start + 5_000))
        assertEquals("0:00", timer.label(start + 25_000))
        val held = PamClockSpec(PamTimeSource(start, 10_000, false), 30_000, null, PamClockFormat.SECONDS)
        assertEquals("20", held.label(start + 9_000))
        assertEquals(-1L, held.nextChangeIn(start + 9_000))
    }

    @Test fun progressFillsByTimeAndHoldsWhenPaused() {
        val bar = PamProgressSpec(PamTimeSource(start, 0, true), 30_000)
        assertEquals(0f, bar.fractionAt(start), 0f)
        assertEquals(0.5f, bar.fractionAt(start + 15_000), 1e-6f)
        assertEquals(1f, bar.fractionAt(start + 60_000), 0f)
        assertTrue(bar.movingAt(start + 29_000))
        assertFalse(bar.movingAt(start + 30_000))
        val held = PamProgressSpec(PamTimeSource(start, 12_000, false), 30_000)
        assertEquals(0.4f, held.fractionAt(start + 20_000), 1e-6f)
        assertFalse(held.movingAt(start + 20_000))
        assertEquals(0f, PamProgressSpec(PamTimeSource(start, 0, true), 0).fractionAt(start + 1), 0f)
    }

    @Test fun revealShowsOneGraphemePerStep() {
        val typed = PamRevealSpec(55, 0, "a")
        assertEquals(0, typed.shown(0, 5))
        assertEquals(0, typed.shown(54, 5))
        assertEquals(1, typed.shown(55, 5))
        assertEquals(5, typed.shown(10_000, 5))
        assertEquals(55L, typed.nextChangeIn(0, 5))
        assertEquals(1L, typed.nextChangeIn(109, 5))
        assertEquals(-1L, typed.nextChangeIn(275, 5))
        val spread = PamRevealSpec(0, 1_000, null)
        assertEquals(2, spread.shown(500, 4))
        assertEquals(4, spread.shown(1_000, 4))
        assertEquals(0, spread.shown(500, 0))
    }
}
