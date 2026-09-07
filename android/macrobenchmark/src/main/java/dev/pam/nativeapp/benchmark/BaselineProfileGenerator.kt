package dev.pam.nativeapp.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = BuildConfig.TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.ensureHome()
        device.requireObject("benchmark-counter").click()
        device.requireObject("benchmark-list-route").click()
        device.requireObject("benchmark-large-list")
        repeat(3) {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight * 3 / 4,
                device.displayWidth / 2,
                device.displayHeight / 4,
                SWIPE_STEPS,
            )
        }
        device.waitForIdle()
    }

    private companion object {
        const val SWIPE_STEPS = 12
        const val UI_TIMEOUT_MS = 5_000L
    }

    private fun UiDevice.requireObject(description: String) =
        wait(Until.findObject(By.desc(description)), UI_TIMEOUT_MS)
            ?: error("Baseline profile target $description was not found")

    private fun UiDevice.ensureHome() {
        if (hasObject(By.desc("benchmark-counter"))) return

        wait(Until.findObject(By.desc("Open the technical lab")), UI_TIMEOUT_MS)?.let {
            it.click()
            requireObject("benchmark-counter")
            return
        }

        pressBack()
        requireObject("benchmark-counter")
    }
}
