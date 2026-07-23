package dev.pam.nativeapp.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class PamNativeBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = STARTUP_ITERATIONS,
        setupBlock = {
            pressHome()
            device.executeShellCommand("pm clear ${BuildConfig.TARGET_PACKAGE}")
        },
        measureBlock = { startActivityAndWait() },
    )

    @Test
    fun propertyPatches() = benchmarkRule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
            TraceSectionMetric("PamNative.decode"),
            TraceSectionMetric("PamNative.mount"),
        ),
        compilationMode = CompilationMode.Full(),
        iterations = INTERACTION_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.ensureHome()
        },
        measureBlock = {
            repeat(PATCHES_PER_ITERATION) {
                device.requireObject("benchmark-counter").click()
            }
            device.waitForIdle()
        },
    )

    @Test
    fun virtualizedListScroll() = benchmarkRule.measureRepeated(
        packageName = BuildConfig.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        iterations = INTERACTION_ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            if (!device.hasObject(By.desc("benchmark-large-list"))) {
                device.ensureHome()
                device.requireObject("benchmark-list-route").click()
            }
            device.wait(Until.hasObject(By.desc("benchmark-large-list")), UI_TIMEOUT_MS)
        },
        measureBlock = {
            repeat(SCROLLS_PER_ITERATION) {
                device.swipeUp()
            }
            device.waitForIdle()
        },
    )

    private fun UiDevice.requireObject(description: String) =
        wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MS)
            ?: error("Benchmark target $description was not found")

    private fun UiDevice.ensureHome() {
        if (hasObject(By.desc("benchmark-counter"))) return
        requireObject("benchmark-back").click()
        wait(Until.hasObject(By.desc("benchmark-counter")), UI_TIMEOUT_MS)
    }

    private fun UiDevice.swipeUp() {
        swipe(
            displayWidth / 2,
            displayHeight * 3 / 4,
            displayWidth / 2,
            displayHeight / 4,
            SWIPE_STEPS,
        )
    }

    private companion object {
        const val STARTUP_ITERATIONS = 10
        const val INTERACTION_ITERATIONS = 5
        const val PATCHES_PER_ITERATION = 30
        const val SCROLLS_PER_ITERATION = 6
        const val SWIPE_STEPS = 12
        const val UI_TIMEOUT_MS = 5_000L
    }
}
