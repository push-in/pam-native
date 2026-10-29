package dev.pam.nativeapp.render

import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Where a platform fling will come to rest: the distance (px) Android's
 * OverScroller spline travels for a release velocity (px/s), so a list can
 * ask for its next page before the fling gets there instead of when it
 * already rests at a temporary end.
 */
internal fun splineFlingDistancePx(velocity: Float, density: Float, friction: Float = ViewConfiguration.getScrollFriction()): Float {
    if (velocity == 0f || friction <= 0f) return 0f
    // OverScroller: g * inches/m * ppi * 0.84 (the platform's physical coefficient).
    val physical = 9.80665f * 39.37f * (density * 160f) * 0.84f
    val l = ln(INFLEXION * abs(velocity) / (friction * physical))
    return (friction * physical * exp(DECELERATION_RATE / (DECELERATION_RATE - 1.0) * l)).toFloat()
}

/**
 * Rows from the end at which `endReached` fires: the threshold (in screens,
 * RN `onEndReachedThreshold`) plus the rows a running fling still projects
 * to travel toward the end.
 */
internal fun endReachedTriggerRows(visibleRows: Int, threshold: Double, flingRemainingPx: Float, rowPx: Float): Int {
    val base = maxOf(1, (visibleRows * threshold).toInt())
    if (flingRemainingPx <= 0f || rowPx <= 0f) return base
    return base + kotlin.math.ceil(flingRemainingPx / rowPx).toInt()
}

private const val INFLEXION = 0.35f
private val DECELERATION_RATE = ln(0.78) / ln(0.9)
