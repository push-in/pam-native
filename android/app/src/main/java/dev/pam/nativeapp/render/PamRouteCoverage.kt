package dev.pam.nativeapp.render

import android.view.View
import android.view.ViewGroup

/**
 * Covered-route policy. A view is covered when one of its route ancestors
 * is hidden by its host: a navigation route below a full-screen route
 * (INVISIBLE; contained modals and form sheets keep the route below
 * VISIBLE, so it stays focused) or an inactive tab scene (GONE).
 * Covered routes keep their native tree, but release decoders and other
 * heavy native resources until they are visible again.
 */
internal object PamRouteCoverage {
    /**
     * [revealing]: a route its host shows on the next frame (a push laid
     * out first, [PamNavigationHost.isRevealing]) does not cover. Media use
     * it to prepare their decoder while that route lays out; modals and
     * route events keep the visibility rule.
     */
    fun isCovered(view: View, revealing: Boolean = false): Boolean {
        var child: View = view
        var parent = view.parent
        while (parent is View) {
            if (child.visibility != View.VISIBLE && isRouteContainer(parent) &&
                !(revealing && parent is PamNavigationHost && parent.isRevealing(child))
            ) {
                return true
            }
            child = parent
            parent = parent.parent
        }
        return false
    }

    private fun isRouteContainer(parent: View): Boolean =
        parent is PamNavigationHost || parent.parent is PamTabHost

    /** A view that re-evaluates its coverage when a route starts or stops being revealed. */
    interface Aware {
        fun onRouteCoverageChanged()
    }

    /** Coverage of [root]'s subtree changed without a visibility change (a reveal began or ended). */
    fun dispatchChanged(root: View) {
        if (root is Aware) root.onRouteCoverageChanged()
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) dispatchChanged(root.getChildAt(index))
        }
    }
}

/**
 * Edge-triggered route focus state for one listener (`on:routeCovered` /
 * `on:routeFocused`): returns the event to dispatch, or null when the
 * coverage did not change. A listener starts focused, so a route mounted
 * covered (prewarmed, kept alive below the stack) reports `covered` once.
 */
internal class RouteFocusTracker {
    private var covered = false

    fun update(nowCovered: Boolean): Boolean? {
        if (nowCovered == covered) return null
        covered = nowCovered
        return nowCovered
    }
}
