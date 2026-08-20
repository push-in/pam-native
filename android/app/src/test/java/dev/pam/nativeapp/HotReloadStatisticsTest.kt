package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HotReloadStatisticsTest {
    @Test
    fun reportsNearestRankP95AndFailureRate() {
        val statistics = HotReloadStatistics(capacity = 32, p95BudgetNanos = 19_000)
        (1L..20L).forEach { duration ->
            statistics.record(HotReloadTiming(duration * 1_000, 100, failed = false))
        }
        statistics.record(HotReloadTiming(50_000, 100, failed = true))

        val snapshot = statistics.snapshot()

        assertEquals(21, snapshot.sampleCount)
        assertEquals(20, snapshot.successfulCount)
        assertEquals(1, snapshot.failureCount)
        assertEquals(19_000, snapshot.p95DurationNanos)
        assertEquals(476, snapshot.failureRateBasisPoints)
        assertTrue(snapshot.p95WithinBudget ?: false)
    }

    @Test
    fun evictsOldestSamplesAndDetectsBudgetRegression() {
        val statistics = HotReloadStatistics(capacity = 2, p95BudgetNanos = 100)
        statistics.record(HotReloadTiming(10, 10, failed = true))
        statistics.record(HotReloadTiming(90, 10, failed = false))
        statistics.record(HotReloadTiming(101, 10, failed = false))

        val snapshot = statistics.snapshot()

        assertEquals(2, snapshot.sampleCount)
        assertEquals(0, snapshot.failureCount)
        assertEquals(101, snapshot.p95DurationNanos)
        assertFalse(snapshot.p95WithinBudget ?: true)
    }

    @Test
    fun emptyAndFailureOnlyWindowsDoNotInventLatency() {
        val statistics = HotReloadStatistics(capacity = 2, p95BudgetNanos = 100)
        assertNull(statistics.snapshot().p95DurationNanos)
        assertNull(statistics.snapshot().p95WithinBudget)

        statistics.record(HotReloadTiming(90, 10, failed = true))

        assertNull(statistics.snapshot().p95DurationNanos)
        assertEquals(10_000, statistics.snapshot().failureRateBasisPoints)
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            HotReloadStatistics(capacity = 0, p95BudgetNanos = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HotReloadStatistics(capacity = 1, p95BudgetNanos = 0)
        }
        val statistics = HotReloadStatistics(capacity = 1, p95BudgetNanos = 1)
        assertThrows(IllegalArgumentException::class.java) {
            statistics.record(HotReloadTiming(-1, 1, failed = false))
        }
    }
}
