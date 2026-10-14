package dev.pam.nativeapp.render

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** `MediaClockFormat` (PHP authority): 1 elapsed .. 5 countdown seconds. */
internal object PamMediaClock {
    const val ELAPSED = 1
    const val REMAINING = 2
    const val ELAPSED_AND_DURATION = 3
    const val DURATION = 4
    const val COUNTDOWN_SECONDS = 5

    fun format(format: Int, positionSeconds: Double, durationSeconds: Double): String {
        val position = positionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val duration = durationSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        val remaining = (duration - position).coerceAtLeast(0.0)
        return when (format) {
            REMAINING -> clock(ceil(remaining - 1e-6))
            ELAPSED_AND_DURATION -> clock(floor(position)) + " / " + clock(floor(duration))
            DURATION -> clock(floor(duration))
            COUNTDOWN_SECONDS -> ceil(remaining - 1e-6).toLong().coerceAtLeast(0).toString()
            else -> clock(floor(position))
        }
    }

    /** Fill fraction of a `mediaProgress` bar, 0 until the duration is known. */
    fun fraction(positionSeconds: Double, durationSeconds: Double): Float {
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0 || !positionSeconds.isFinite()) return 0f
        return (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f)
    }

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }
}
