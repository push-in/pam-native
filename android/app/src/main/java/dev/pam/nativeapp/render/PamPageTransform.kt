package dev.pam.nativeapp.render

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.sqrt

/** `ScrollPageTransform` values (PHP enum, starting at 1). */
internal object PamPageTransformKind {
    const val NONE = 0
    const val CUBE = 1
}

/**
 * One face of the cube pager for a page at relative position [t]
 * (-1 = one page to the left, 0 = front, 1 = right): it rotates around the
 * edge it shares with the neighbour ([pivotFraction] of its width), so both
 * faces stay glued, and darkens by [shade] while it turns away.
 */
internal data class PamCubeFace(
    val angleDeg: Float,
    val pivotFraction: Float,
    val shade: Float,
)

internal const val PAM_CUBE_MAX_ANGLE_DEG = 90f
internal const val PAM_CUBE_MAX_SHADE = 0.55f

/** Perspective of the cube, as a multiple of the page width. */
internal const val PAM_CUBE_PERSPECTIVE_WIDTHS = 2.4f

internal fun pamCubeFace(t: Float): PamCubeFace {
    val clamped = t.coerceIn(-1f, 1f)
    return PamCubeFace(
        angleDeg = clamped * PAM_CUBE_MAX_ANGLE_DEG,
        pivotFraction = if (clamped < 0f) 1f else 0f,
        shade = abs(clamped) * PAM_CUBE_MAX_SHADE,
    )
}

/**
 * React Native's `perspective` → `View.cameraDistance` (BaseViewManager:
 * density² × perspective(dp) × √5).
 */
internal fun pamCameraDistance(perspectiveDp: Float, density: Float): Float =
    density * density * perspectiveDp * sqrt(5f)

/**
 * Applies a per-page transform to the pages of a horizontal pager from its
 * scroll offset. Runs only from scroll/layout callbacks, so nothing works
 * while the pager is still. The pages are the children of the content row:
 * the single wider-than-the-viewport child of the content, or the content's
 * own children when the row was flattened.
 */
internal class PamPageTransformer {
    var kind = PamPageTransformKind.NONE
        private set
    private val touched = HashSet<View>()
    private val overlays = HashMap<View, ColorDrawable>()

    fun setKind(value: Int) {
        val next = if (value == PamPageTransformKind.CUBE) value else PamPageTransformKind.NONE
        if (next == kind) return
        kind = next
        if (next == PamPageTransformKind.NONE) reset()
    }

    fun apply(content: ViewGroup, scrollX: Int, viewportWidth: Int) {
        if (kind == PamPageTransformKind.NONE || viewportWidth <= 0) return
        val row = pageRow(content, viewportWidth)
        val density = content.resources.displayMetrics.density
        val cameraDistance = pamCameraDistance(viewportWidth / density * PAM_CUBE_PERSPECTIVE_WIDTHS, density)
        val rowLeft = if (row === content) 0 else row.left
        touched.retainAll { it.parent === row }
        overlays.keys.retainAll(touched)
        for (index in 0 until row.childCount) {
            val page = row.getChildAt(index)
            val width = page.width
            if (width <= 0) continue
            val t = (rowLeft + page.left - scrollX).toFloat() / width
            if (abs(t) >= 1f) {
                if (page in touched) clear(page)
                continue
            }
            val face = pamCubeFace(t)
            page.cameraDistance = cameraDistance
            page.pivotX = width * face.pivotFraction
            page.pivotY = page.height / 2f
            page.rotationY = face.angleDeg
            shade(page, face.shade)
            touched += page
        }
    }

    fun reset() {
        touched.forEach(::clear)
        touched.clear()
        overlays.clear()
    }

    private fun pageRow(content: ViewGroup, viewportWidth: Int): ViewGroup {
        if (content.childCount == 1) {
            val only = content.getChildAt(0)
            if (only is ViewGroup && only.width > viewportWidth) return only
        }
        return content
    }

    private fun shade(page: View, amount: Float) {
        val current = page.foreground
        val overlay = when {
            current == null -> ColorDrawable(Color.BLACK).also {
                page.foreground = it
                overlays[page] = it
            }
            current === overlays[page] -> current as ColorDrawable
            else -> return // the page owns its foreground (ripple); leave it alone
        }
        overlay.alpha = (amount.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }

    private fun clear(page: View) {
        page.rotationY = 0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            page.resetPivot()
        } else {
            page.pivotX = page.width / 2f
            page.pivotY = page.height / 2f
        }
        val overlay = overlays.remove(page)
        if (overlay != null && page.foreground === overlay) page.foreground = null
    }
}
