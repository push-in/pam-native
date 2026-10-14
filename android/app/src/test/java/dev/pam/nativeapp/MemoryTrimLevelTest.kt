package dev.pam.nativeapp

import android.content.ComponentCallbacks2
import dev.pam.nativeapp.PamActivity.Companion.MEMORY_PRESSURE_CRITICAL
import dev.pam.nativeapp.PamActivity.Companion.MEMORY_PRESSURE_MODERATE
import dev.pam.nativeapp.PamActivity.Companion.memoryPressureForTrimLevel
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("DEPRECATION")
class MemoryTrimLevelTest {
    @Test
    fun goingToBackgroundOnlyHalvesTheCaches() {
        for (level in listOf(
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        )) {
            assertEquals("level $level", MEMORY_PRESSURE_MODERATE, memoryPressureForTrimLevel(level))
        }
    }

    @Test
    fun runningLevelsBelowCriticalOnlyHalveTheCaches() {
        assertEquals(MEMORY_PRESSURE_MODERATE, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertEquals(MEMORY_PRESSURE_MODERATE, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
    }

    @Test
    fun runningCriticalAndCompleteEmptyTheCaches() {
        assertEquals(MEMORY_PRESSURE_CRITICAL, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(MEMORY_PRESSURE_CRITICAL, memoryPressureForTrimLevel(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
