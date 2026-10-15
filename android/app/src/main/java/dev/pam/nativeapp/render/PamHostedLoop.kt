package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.View

/**
 * The infinite animation of a self-animating drawable (spinner, button
 * loading arc) that ticks only while its host view is on screen.
 *
 * A drawable is visible from construction, and a renderer can create the
 * view and start the spinner before (or without ever) attaching it: a
 * prewarmed or cached subtree, a hidden route, a recycled list cell. An
 * unconditional `ValueAnimator.start()` then drives Choreographer callbacks
 * at the display rate forever, also with the app in the background (the
 * Zé Chat inbox kept the main thread at ~60 wakeups/s and ~4% CPU). The loop
 * starts only when requested, visible and shown in a visible window; a
 * tick that finds the host gone suspends it, and the next draw (the host is
 * on screen again) or the host's aggregated visibility resumes it.
 */
internal class PamHostedLoop(
    private val drawable: Drawable,
    private val animator: ValueAnimator,
) {
    var requested = false
        private set

    fun request(on: Boolean) {
        requested = on
        if (on) resume() else suspend()
    }

    fun resume() {
        if (requested && drawable.isVisible && isHostShowing(drawable) && !animator.isStarted) {
            animator.start()
        }
    }

    fun suspend() {
        if (animator.isStarted) animator.cancel()
    }

    /** For the update listener: false (and suspended) once the host left the screen. */
    fun tick(): Boolean {
        if (isHostShowing(drawable)) return true
        suspend()
        return false
    }

    val isTicking: Boolean get() = animator.isStarted
}

internal fun isHostShowing(drawable: Drawable): Boolean {
    val host = drawable.callback as? View ?: return false
    return host.isAttachedToWindow && host.windowVisibility == View.VISIBLE && host.isShown
}
