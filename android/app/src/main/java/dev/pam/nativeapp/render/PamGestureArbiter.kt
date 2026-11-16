package dev.pam.nativeapp.render

import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import android.widget.AbsSeekBar
import dev.pam.nativeapp.R

/**
 * A view that may consume a drag under the finger and says so itself (a
 * scroller that can be turned off, a pan with a direction, a drawing
 * surface). Views without it are asked through `canScrollHorizontally` /
 * `canScrollVertically`, like `ViewPager.canScroll`.
 */
internal interface PamDragOwner {
    /**
     * How strongly this view owns a drag along [horizontal] (else vertical)
     * axis whose finger moves toward [fingerSign] (+1 right/down, -1
     * left/up): [PamGestureArbiter.NONE], [PamGestureArbiter.DIRECTIONAL] or
     * [PamGestureArbiter.EXCLUSIVE].
     */
    fun dragOwnership(horizontal: Boolean, fingerSign: Int): Int

    /** A scroller (its drags move content), as opposed to a pan, a slider or a canvas. */
    val scrollsContent: Boolean get() = false
}

/**
 * Who owns a drag under the finger, so no gesture steals another's (UIKit's
 * `require(toFail:)`, react-native-gesture-handler's native view gestures,
 * `ViewPager.canScroll`). A container gesture (the navigator's swipe-back, a
 * pull to refresh, a sheet's drag to close, swipeable tabs, a pan around
 * content) asks before it takes a drag and yields to the content under the
 * finger that would consume that drag itself:
 *
 * - [DIRECTIONAL]: a scroller that can still scroll the way the finger moves
 *   (a pager with a previous page, a tab strip scrolled away from its start,
 *   a list scrolled down), a pan or swipe detector whose direction admits
 *   the motion (a horizontal `Drag`, swipe to reply), a slider.
 * - [EXCLUSIVE]: content that owns every drag over it, even where a
 *   container gesture has priority (the navigator's edge): a drawing
 *   surface, a zoomed photo, a view marked `blocksNavigationGesture`.
 *
 * The walk follows the touch geometry (offsets, scroll, transforms) through
 * every visible view under the point, as `ViewPager.canScroll` does. It runs
 * once per drag decision, never per frame.
 */
internal object PamGestureArbiter {
    const val NONE = 0
    const val DIRECTIONAL = 1
    const val EXCLUSIVE = 2

    private const val MAX_DEPTH = 64

    /**
     * Set while a list claims a touch on ACTION_DOWN only to keep a platform
     * ScrollView ancestor from intercepting it (PamRecyclerList): such a
     * claim says nothing about the axis, so a navigator still asks the
     * arbiter instead of treating it as the list taking the drag.
     */
    var claimingForScrollAncestor = false
        private set

    /** [view]'s parent chain stops intercepting ([claim]) for a ScrollView ancestor's sake. */
    fun claimForScrollAncestor(view: View, claim: Boolean) {
        val previous = claimingForScrollAncestor
        claimingForScrollAncestor = claim
        try {
            view.parent?.requestDisallowInterceptTouchEvent(claim)
        } finally {
            claimingForScrollAncestor = previous
        }
    }

    /** Marks [view] as owning every drag over it against navigation gestures. */
    fun setBlocksNavigationGesture(view: View, blocks: Boolean) {
        view.setTag(R.id.pam_blocks_navigation_gesture, if (blocks) true else null)
    }

    fun blocksNavigationGesture(view: View): Boolean =
        view.getTag(R.id.pam_blocks_navigation_gesture) == true

    /**
     * The strongest ownership of a drag among [view] and its descendants
     * under ([x], [y]) in [view]'s own coordinates.
     */
    fun ownership(
        view: View,
        x: Float,
        y: Float,
        horizontal: Boolean,
        fingerSign: Int,
        navigation: Boolean,
        includeSelf: Boolean = true,
    ): Int = visit(view, x, y, includeSelf, 0) { own(it, horizontal, fingerSign, navigation) }

    /**
     * Like [ownership] over [view]'s descendants, counting scrollers only: a
     * pan around content (a dismiss drag) yields to a list that can still
     * scroll, never to another detector, which composes with it instead.
     */
    fun scrollOwnership(view: ViewGroup, x: Float, y: Float, horizontal: Boolean, fingerSign: Int): Int =
        visit(view, x, y, includeSelf = false, depth = 0) { ownScroll(it, horizontal, fingerSign) }

    /**
     * [ownership] of [child] and its descendants under ([x], [y]) given in
     * [parent]'s coordinates (a navigator asks its top route only).
     */
    fun ownershipOfChild(
        parent: ViewGroup,
        child: View,
        x: Float,
        y: Float,
        horizontal: Boolean,
        fingerSign: Int,
        navigation: Boolean,
    ): Int {
        val point = FloatArray(2)
        if (!childPoint(parent, child, x, y, point)) return NONE
        return visit(child, point[0], point[1], includeSelf = true, depth = 0) {
            own(it, horizontal, fingerSign, navigation)
        }
    }

    /**
     * Views under ([x], [y]) of [root] (its descendants, outermost first)
     * that could own a drag, resolved once when a touch goes down; a
     * container asks [ownership] of them per move while scroll offsets change
     * (a list that reaches its top hands the drag over).
     */
    fun candidatesAt(root: ViewGroup, x: Float, y: Float): List<View> {
        val out = ArrayList<View>(4)
        collect(root, x, y, out, includeSelf = false, depth = 0)
        return out
    }

    fun ownership(
        candidates: List<View>,
        horizontal: Boolean,
        fingerSign: Int,
        navigation: Boolean,
    ): Int {
        var best = NONE
        for (view in candidates) {
            if (!view.isAttachedToWindow) continue
            best = maxOf(best, own(view, horizontal, fingerSign, navigation))
            if (best >= EXCLUSIVE) return best
        }
        return best
    }

    /** The ownership [view] itself declares, without its descendants. */
    fun own(view: View, horizontal: Boolean, fingerSign: Int, navigation: Boolean): Int {
        if (navigation && blocksNavigationGesture(view)) return EXCLUSIVE
        if (view is PamDragOwner) return view.dragOwnership(horizontal, fingerSign)
        if (view is AbsSeekBar) {
            return if (horizontal && view.isEnabled) DIRECTIONAL else NONE
        }
        val scrolls = if (horizontal) {
            view.canScrollHorizontally(-fingerSign)
        } else {
            view.canScrollVertically(-fingerSign)
        }
        return if (scrolls) DIRECTIONAL else NONE
    }

    private fun ownScroll(view: View, horizontal: Boolean, fingerSign: Int): Int {
        if (view is PamDragOwner) {
            return if (view.scrollsContent) view.dragOwnership(horizontal, fingerSign) else NONE
        }
        if (view is AbsSeekBar) return NONE
        val scrolls = if (horizontal) {
            view.canScrollHorizontally(-fingerSign)
        } else {
            view.canScrollVertically(-fingerSign)
        }
        return if (scrolls) DIRECTIONAL else NONE
    }

    private fun visit(
        view: View,
        x: Float,
        y: Float,
        includeSelf: Boolean,
        depth: Int,
        owner: (View) -> Int,
    ): Int {
        val pointerEvents = pointerEvents(view)
        if (pointerEvents == POINTER_EVENTS_NONE) return NONE
        var best = if (includeSelf && pointerEvents != POINTER_EVENTS_BOX_NONE) {
            owner(view)
        } else {
            NONE
        }
        if (best >= EXCLUSIVE || pointerEvents == POINTER_EVENTS_BOX_ONLY) return best
        if (view !is ViewGroup || depth >= MAX_DEPTH) return best
        val point = FloatArray(2)
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (!childPoint(view, child, x, y, point)) continue
            best = maxOf(
                best,
                visit(child, point[0], point[1], true, depth + 1, owner),
            )
            if (best >= EXCLUSIVE) return best
        }
        return best
    }

    private fun collect(
        view: View,
        x: Float,
        y: Float,
        out: MutableList<View>,
        includeSelf: Boolean,
        depth: Int,
    ) {
        val pointerEvents = pointerEvents(view)
        if (pointerEvents == POINTER_EVENTS_NONE) return
        if (includeSelf && pointerEvents != POINTER_EVENTS_BOX_NONE && isCandidate(view)) out += view
        if (pointerEvents == POINTER_EVENTS_BOX_ONLY || view !is ViewGroup || depth >= MAX_DEPTH) return
        val point = FloatArray(2)
        for (index in view.childCount - 1 downTo 0) {
            val child = view.getChildAt(index)
            if (!childPoint(view, child, x, y, point)) continue
            collect(child, point[0], point[1], out, true, depth + 1)
        }
    }

    /** RN `pointerEvents`: touches skip `none` subtrees and `box-none` views, `box-only` keeps them from children. */
    private fun pointerEvents(view: View): Int =
        (view as? PamContainer)?.pointerEventsMode ?: POINTER_EVENTS_AUTO

    /** A view that may own a drag during this touch (scroll offsets may still change). */
    private fun isCandidate(view: View): Boolean =
        view is PamDragOwner || view is AbsSeekBar || blocksNavigationGesture(view) ||
            view.canScrollVertically(1) || view.canScrollVertically(-1) ||
            view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)

    private val inverse = Matrix()

    /**
     * Maps ([x], [y]) in [parent] to [child] like touch dispatch does
     * (scroll, offsets, the child's transform): false when it misses it or
     * the child is not visible.
     */
    private fun childPoint(parent: ViewGroup, child: View, x: Float, y: Float, out: FloatArray): Boolean {
        if (child.visibility != View.VISIBLE) return false
        out[0] = x + parent.scrollX - child.left
        out[1] = y + parent.scrollY - child.top
        val matrix = child.matrix
        if (!matrix.isIdentity) {
            if (!matrix.invert(inverse)) return false
            inverse.mapPoints(out)
        }
        return out[0] >= 0f && out[1] >= 0f && out[0] < child.width && out[1] < child.height
    }
}
