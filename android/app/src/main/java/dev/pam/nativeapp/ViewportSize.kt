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

/** Per-edge maximum: an unfocused window never loses a known safe edge. */
internal fun retainedSafeAreaInsets(
    previous: androidx.core.graphics.Insets,
    reported: androidx.core.graphics.Insets,
): androidx.core.graphics.Insets = androidx.core.graphics.Insets.of(
    maxOf(previous.left, reported.left),
    maxOf(previous.top, reported.top),
    maxOf(previous.right, reported.right),
    maxOf(previous.bottom, reported.bottom),
)
