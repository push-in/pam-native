package dev.pam.nativeapp.render

import android.graphics.Rect
import android.view.View
import android.widget.Button

internal fun requiresMinimumTouchTarget(view: View): Boolean =
    view is PamPressable || view is Button

internal fun minimumTouchTargetInsets(width: Int, height: Int, minimum: Int): Rect {
    val horizontal = (minimum - width).coerceAtLeast(0)
    val vertical = (minimum - height).coerceAtLeast(0)
    return Rect(
        horizontal / 2,
        vertical / 2,
        horizontal - horizontal / 2,
        vertical - vertical / 2,
    )
}
