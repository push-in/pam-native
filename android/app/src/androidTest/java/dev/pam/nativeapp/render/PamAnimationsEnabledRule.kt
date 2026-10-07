package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a test with system animations at 1x, then restores the device setting.
 *
 * Emulators for CI disable animations (all three scales at 0), which the
 * renderer correctly treats as reduced motion: motion settles at once. Tests
 * that observe an animation in flight need real durations, so they switch the
 * animator scale on and wait until this process has applied it.
 */
class PamAnimationsEnabledRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previous = SCALES.associateWith(::readScale)
            if (previous.values.all { it == 1f } && ValueAnimator.getDurationScale() == 1f) {
                base.evaluate()
                return
            }
            SCALES.forEach { writeScale(it, 1f) }
            try {
                awaitProcessScale(1f)
                base.evaluate()
            } finally {
                previous.forEach { (name, value) -> writeScale(name, value) }
                awaitProcessScale(previous.getValue(ANIMATOR_SCALE))
            }
        }
    }

    private fun readScale(name: String): Float =
        shell("settings get global $name").trim().toFloatOrNull() ?: 1f

    private fun writeScale(name: String, value: Float) {
        shell("settings put global $name $value")
    }

    /** The window manager pushes the new scale to every app process. */
    private fun awaitProcessScale(value: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (ValueAnimator.getDurationScale() != value) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Animator duration scale stayed ${ValueAnimator.getDurationScale()}, expected $value"
            }
            awaitFrames(instrumentation)
        }
    }

    private companion object {
        const val ANIMATOR_SCALE = "animator_duration_scale"
        val SCALES = listOf("window_animation_scale", "transition_animation_scale", ANIMATOR_SCALE)
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }
    }
}
