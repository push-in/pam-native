package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HotReloadLatencyTest {
    @Test
    fun measuresOneConfirmedVersionThroughItsFirstCommittedFrame() {
        val latency = HotReloadLatency()
        latency.begin(1_000, 4_096)

        val timing = latency.complete(3_500, failed = false)

        assertEquals(2_500L, timing?.durationNanos)
        assertEquals(4_096L, timing?.bundleBytes)
        assertFalse(timing?.failed ?: true)
        assertNull(latency.complete(4_000, failed = false))
    }

    @Test
    fun newerVersionReplacesPendingMeasurementAndFailureCompletesIt() {
        val latency = HotReloadLatency()
        latency.begin(1_000, 100)
        latency.begin(2_000, 200)

        val timing = latency.complete(2_500, failed = true)

        assertEquals(500L, timing?.durationNanos)
        assertEquals(200L, timing?.bundleBytes)
        assertTrue(timing?.failed ?: false)
    }

    @Test
    fun rejectsInvalidMonotonicTimeAndNegativeTransfers() {
        val latency = HotReloadLatency()
        assertThrows(IllegalArgumentException::class.java) { latency.begin(0, 1) }
        assertThrows(IllegalArgumentException::class.java) { latency.begin(1, -1) }
        // No transfer (every file already on the device) and transfers far
        // beyond the former 16 MiB bundle cap are both valid.
        latency.begin(1, 0)
        latency.begin(1, 64L * 1024 * 1024)
        assertEquals(64L * 1024 * 1024, latency.complete(2, failed = false)?.bundleBytes)
    }
}
