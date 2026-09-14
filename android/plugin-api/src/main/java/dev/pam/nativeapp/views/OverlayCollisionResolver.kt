package dev.pam.nativeapp.views

import kotlin.math.max
import kotlin.math.min

enum class OverlayPlacement(val code: Int) {
    Top(1), TopStart(2), TopEnd(3), Bottom(4), BottomStart(5), BottomEnd(6),
    Left(7), LeftTop(8), LeftBottom(9), Right(10), RightTop(11), RightBottom(12), Center(13),
}

/** Screen-space bounds shared by native overlay hosts. */
data class OverlayBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Selects a preferred origin without hiding its anchor when another candidate fits. */
object OverlayCollisionResolver {
    fun selectCandidate(
        anchor: OverlayBounds,
        viewport: OverlayBounds,
        width: Float,
        height: Float,
        candidates: List<Pair<Float, Float>>,
    ): Int {
        require(candidates.isNotEmpty())
        var bestIndex = 0
        var bestOverlap = Double.POSITIVE_INFINITY
        var bestOverflow = Double.POSITIVE_INFINITY
        candidates.forEachIndexed { index, origin ->
            val x = origin.first.coerceIn(viewport.left, max(viewport.left, viewport.right - width))
            val y = origin.second.coerceIn(viewport.top, max(viewport.top, viewport.bottom - height))
            val overlapWidth = max(0f, min(x + width, anchor.right) - max(x, anchor.left))
            val overlapHeight = max(0f, min(y + height, anchor.bottom) - max(y, anchor.top))
            val overlap = overlapWidth.toDouble() * overlapHeight.toDouble()
            val overflow = max(0f, viewport.left - origin.first).toDouble() +
                max(0f, origin.first + width - viewport.right) +
                max(0f, viewport.top - origin.second) +
                max(0f, origin.second + height - viewport.bottom)
            if (overlap < bestOverlap || (overlap == bestOverlap && overflow < bestOverflow)) {
                bestIndex = index
                bestOverlap = overlap
                bestOverflow = overflow
            }
        }
        return bestIndex
    }
}
