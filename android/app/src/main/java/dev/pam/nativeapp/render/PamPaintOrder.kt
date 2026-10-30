package dev.pam.nativeapp.render

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import dev.pam.nativeapp.R
import kotlin.math.max

/**
 * Android composites siblings by Z before document order, so a native
 * `elevation` (the Material shadow) would also lift a view above its later
 * siblings. Other platforms paint siblings in document order, ordered only by
 * zIndex. This keeps the shadow and restores that order: each sibling gets the
 * smallest Z that is at least its own and never below a sibling it must cover.
 * Views lifted only for ordering cast no shadow.
 */
internal object PamPaintOrder {
    /** Gap that keeps a higher zIndex strictly above an equal Z. */
    const val STEP = 0.01f

    /**
     * Z for each child, in child order, from its authored zIndex and elevation
     * (pixels). Without any elevation the zIndex itself is the Z.
     */
    fun resolve(zIndex: FloatArray, elevation: FloatArray): FloatArray {
        require(zIndex.size == elevation.size)
        val count = zIndex.size
        val out = FloatArray(count) { zIndex[it] }
        if (elevation.none { it > 0f }) return out
        val order = (0 until count).sortedWith(compareBy<Int>({ zIndex[it] }, { it }))
        var previous = Float.NaN
        var previousIndex = -1
        for (child in order) {
            val own = if (elevation[child] > 0f) max(elevation[child], zIndex[child]) else zIndex[child]
            out[child] = when {
                previous.isNaN() -> own
                // Equal Z keeps child order, so a later child may share it.
                child > previousIndex -> max(own, previous)
                else -> max(own, previous + STEP)
            }
            previous = out[child]
            previousIndex = child
        }
        return out
    }

    fun authoredZIndex(view: View): Float = (view.getTag(R.id.pam_stacking_z_index) as? Float) ?: 0f

    fun setAuthoredZIndex(view: View, zIndex: Float) {
        view.setTag(R.id.pam_stacking_z_index, if (zIndex == 0f) null else zIndex)
    }

    /** A view drawn above its siblings while it follows the keyboard. */
    fun setKeyboardLifted(view: View, lifted: Boolean) {
        view.setTag(R.id.pam_stacking_keyboard_lift, if (lifted) true else null)
    }

    private fun keyboardLifted(view: View): Boolean = view.getTag(R.id.pam_stacking_keyboard_lift) == true

    /** Restacks [view]'s siblings, or sets its own Z while it is detached. */
    fun apply(view: View) {
        val parent = view.parent as? ViewGroup
        if (parent == null) {
            val zIndex = authoredZIndex(view)
            setZ(view, if (view.elevation > 0f) max(view.elevation, zIndex) else zIndex)
            return
        }
        restack(parent)
    }

    /** Called after a child joins [parent]; free unless a sibling is elevated. */
    fun childAdded(parent: ViewGroup, child: View) {
        if (child.elevation > 0f || parent.getTag(R.id.pam_stacking_elevated) == true) restack(parent)
    }

    fun restack(parent: ViewGroup) {
        val count = parent.childCount
        if (count == 0) return
        val zIndex = FloatArray(count)
        val elevation = FloatArray(count)
        var elevated = false
        for (index in 0 until count) {
            val child = parent.getChildAt(index)
            zIndex[index] = authoredZIndex(child)
            elevation[index] = child.elevation
            if (child.elevation > 0f) elevated = true
        }
        parent.setTag(R.id.pam_stacking_elevated, if (elevated) true else null)
        val resolved = resolve(zIndex, elevation)
        var top = Float.NEGATIVE_INFINITY
        for (index in 0 until count) {
            val child = parent.getChildAt(index)
            if (!keyboardLifted(child)) {
                setZ(child, resolved[index])
                top = max(top, resolved[index])
            }
        }
        for (index in 0 until count) {
            val child = parent.getChildAt(index)
            if (keyboardLifted(child)) setZ(child, max(resolved[index], top + 1f))
        }
    }

    private fun setZ(view: View, z: Float) {
        if (view.z != z) view.z = z
        // A Z above the authored elevation only orders the view; it must not
        // draw a shadow the author never asked for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = if (view.elevation > 0f) Color.BLACK else Color.TRANSPARENT
            if (view.outlineSpotShadowColor != shadow) {
                view.outlineSpotShadowColor = shadow
                view.outlineAmbientShadowColor = shadow
            }
        }
    }
}
