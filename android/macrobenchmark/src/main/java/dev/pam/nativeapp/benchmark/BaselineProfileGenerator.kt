package dev.pam.nativeapp.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
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
        device.wait(Until.findObject(By.desc("benchmark-counter")), UI_TIMEOUT_MS)?.click()
        device.wait(Until.findObject(By.desc("benchmark-list-route")), UI_TIMEOUT_MS)?.click()
        device.wait(Until.hasObject(By.desc("benchmark-large-list")), UI_TIMEOUT_MS)
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
}
