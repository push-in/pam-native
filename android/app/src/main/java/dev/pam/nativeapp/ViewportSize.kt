package dev.pam.nativeapp

/**
 * Prefer the viewport Android has actually laid out. On some devices,
 * currentWindowMetrics briefly keeps the previous orientation's bounds after
 * onConfigurationChanged. Sending those stale bounds to PAM leaves responsive
 * navigation and Yoga frames in landscape after the native window is portrait.
 */
internal fun resolvedViewportSize(
    laidOutWidth: Int,
    laidOutHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
): Pair<Int, Int> = if (laidOutWidth > 0 && laidOutHeight > 0) {
    laidOutWidth to laidOutHeight
} else {
    windowWidth.coerceAtLeast(0) to windowHeight.coerceAtLeast(0)
}
