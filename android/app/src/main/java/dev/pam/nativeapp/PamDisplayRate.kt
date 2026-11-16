package dev.pam.nativeapp

import android.view.Display

/**
 * The fastest rate the display runs at its current resolution. Adaptive
 * panels report 60 Hz while idle and switch to 90/120 Hz as soon as content
 * moves, so frame work is sized for the peak: a 120 Hz frame never carries a
 * 60 Hz slice of work (iOS uses maximumFramesPerSecond the same way).
 */
internal fun peakRefreshRate(display: Display): Float {
    val current = display.mode
    return peakRefreshRate(
        current.physicalWidth,
        current.physicalHeight,
        display.refreshRate,
        display.supportedModes.map { DisplayRateMode(it.physicalWidth, it.physicalHeight, it.refreshRate) },
    )
}

internal data class DisplayRateMode(val width: Int, val height: Int, val refreshRate: Float)

internal fun peakRefreshRate(
    width: Int,
    height: Int,
    current: Float,
    modes: List<DisplayRateMode>,
): Float {
    val peak = modes
        .filter { it.width == width && it.height == height && it.refreshRate.isFinite() }
        .maxOfOrNull { it.refreshRate }
    return maxOf(peak ?: current, current).coerceAtLeast(1f)
}
