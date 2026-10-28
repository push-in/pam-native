package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class HotReloadBackoffTest {
    @Test
    fun retryBacksOffToThirtySecondsWhenTheDevServerStaysAway() {
        val delays = (1..12).map { HotReloadClient.backoffDelayMillis(it) }

        assertEquals(listOf(300L, 600L, 1_200L, 2_000L, 2_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
        assertEquals(30_000L, HotReloadClient.backoffDelayMillis(500))
        assertEquals(300L, HotReloadClient.backoffDelayMillis(0))
    }
}
