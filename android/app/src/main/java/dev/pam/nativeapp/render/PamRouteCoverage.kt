package dev.pam.nativeapp.render

import android.view.View

/**
 * Covered-route policy. A view is covered when one of its route ancestors
 * is hidden by its host: a navigation route below a full-screen route
 * (INVISIBLE; contained modals and form sheets keep the route below
 * VISIBLE, so it stays focused) or an inactive tab scene (GONE).
 * Covered routes keep their native tree, but release decoders and other
 * heavy native resources until they are visible again.
 */
internal object PamRouteCoverage {
    fun isCovered(view: View): Boolean {
        var child: View = view
        var parent = view.parent
        while (parent is View) {
            if (child.visibility != View.VISIBLE && isRouteContainer(parent)) return true
            child = parent
            parent = parent.parent
        }
        return false
    }

    private fun isRouteContainer(parent: View): Boolean =
        parent is PamNavigationHost || parent.parent is PamTabHost
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
