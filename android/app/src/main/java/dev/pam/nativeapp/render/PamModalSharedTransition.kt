package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator

/**
 * Shared elements between a Modal and the window under it (the screen, or
 * the Modal below). It is the same `shared` tag contract as routes: when the
 * Modal opens, every tag on screen below that the Modal also shows flies into
 * it; when it closes (hidden or unmounted), the Modal's tags fly home to the
 * same tag below (the instance they came from while it is on screen). Media
 * keep the decoded picture, crop and radius morph; a dragged element
 * (`Drag::holdAt`) starts home from where the finger left it.
 *
 * The flights are drawn in the Modal window's decor overlay (the topmost
 * window), so the Modal content can fade under them. While anything flies,
 * the Modal fades in step with the flights instead of its own animation.
 */
internal class PamModalSharedTransition(private val content: ViewGroup) {
    private var host: ViewGroup? = null
    private var elements: PamSharedElements? = null
    private var animator: ValueAnimator? = null
    private var shade: View? = null
    private var pendingEnd: ((Boolean) -> Unit)? = null

    val running: Boolean get() = animator != null

    fun activeCount(): Int = elements?.count ?: 0

    fun activeFrames(): List<FloatArray> = elements?.frames().orEmpty()

    private fun elementsFor(window: ViewGroup): PamSharedElements {
        elements?.takeIf { host === window }?.let { return it }
        elements?.clear()
        host = window
        return PamSharedElements(window).also { elements = it }
    }

    /**
     * First frame of the Modal in [window]: pairs the tags on screen in
     * [below] with the Modal's (taking over a flight launched by the tap that
     * opened it). True when something flies; the Modal content then fades in
     * with the flights, from what a launch backdrop already covers.
     */
    fun enter(window: ViewGroup, below: View, defaultDurationMs: Long): Boolean {
        cancel()
        if (!hasTag(content)) return false
        val flights = elementsFor(window)
        flights.adoptLaunch()
        flights.prepare(content, below, defaultDurationMs, popping = false)
        if (flights.isEmpty) return false
        val floor = flights.fadeFloor
        run(flights, onFrame = { eased -> content.alpha = modalSharedEnterAlpha(floor, eased) }) {
            content.alpha = 1f
        }
        return true
    }

    /**
     * The Modal closes: its tags fly home into [below] while the content
     * fades out. [shadeColor] covers the window while the content's own views
     * are already gone (an unmounted Modal), fading with it. [onEnd] runs
     * once, with false when the transition was cut short. False when nothing
     * flies (the caller then runs its own exit).
     */
    fun exit(
        window: ViewGroup,
        below: View,
        defaultDurationMs: Long,
        shadeColor: Int?,
        onEnd: (Boolean) -> Unit,
    ): Boolean {
        cancel()
        if (!hasTag(content)) return false
        val flights = elementsFor(window)
        val cover = shadeColor?.let { color ->
            View(window.context).apply {
                setBackgroundColor(color)
                layout(0, 0, window.width, window.height)
            }
        }
        // Under the flights: the overlay draws in insertion order.
        cover?.let(window.overlay::add)
        flights.prepare(below, content, defaultDurationMs, popping = true)
        if (flights.isEmpty) {
            cover?.let(window.overlay::remove)
            return false
        }
        shade = cover
        pendingEnd = onEnd
        val from = content.alpha
        run(flights, onFrame = { eased ->
            content.alpha = modalSharedExitAlpha(from, eased)
            cover?.alpha = modalSharedExitAlpha(1f, eased)
        }) {
            removeShade()
            pendingEnd = null
            onEnd(true)
        }
        return true
    }

    private fun run(flights: PamSharedElements, onFrame: (Float) -> Unit, onEnd: () -> Unit) {
        val started = ValueAnimator.ofFloat(0f, 1f)
        animator = started
        started.duration = flights.durationMs.coerceIn(1L, 2_000L)
        // Linear time: every flight applies its own curve; the fade follows
        // the longest one.
        started.interpolator = LinearInterpolator()
        started.addUpdateListener {
            val time = it.animatedValue as Float
            flights.apply(time)
            onFrame(flights.eased(time).coerceIn(0f, 1f))
        }
        started.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (cancelled || animator !== started) return
                animator = null
                flights.finish()
                onEnd()
            }
        })
        flights.apply(0f)
        onFrame(0f)
        started.start()
    }

    /** Lands every flight at once and restores the views; a pending exit ends (not completed). */
    fun cancel() {
        val running = animator ?: return
        animator = null
        running.cancel()
        elements?.finish()
        removeShade()
        pendingEnd?.let { end ->
            pendingEnd = null
            end(false)
        }
    }

    fun release() {
        cancel()
        elements?.clear()
        elements = null
        host = null
    }

    private fun removeShade() {
        val cover = shade ?: return
        shade = null
        host?.overlay?.remove(cover)
    }

    companion object {
        /** Whether any view under [root] carries a shared tag (cheap: stops at the first). */
        fun hasTag(root: View): Boolean {
            (root.getTag(dev.pam.nativeapp.R.id.pam_shared_transition_tag) as? String)?.let {
                if (it.isNotEmpty()) return true
            }
            if (root !is ViewGroup) return false
            for (index in 0 until root.childCount) if (hasTag(root.getChildAt(index))) return true
            return false
        }

        /**
         * The color that covers [content]'s window: the topmost view (down to
         * three levels) that fills at least 90% of it with a solid
         * background, with that view's opacity. Null when nothing does.
         */
        fun coverColor(content: ViewGroup, skip: View? = null): Int? {
            val area = content.width.toFloat() * content.height
            if (area <= 0f) return null
            val layers = ArrayList<ModalCoverLayer>(4)
            fun visit(group: ViewGroup, depth: Int, alpha: Float) {
                for (index in group.childCount - 1 downTo 0) {
                    val child = group.getChildAt(index)
                    if (child === skip || child.visibility != View.VISIBLE) continue
                    val opacity = alpha * child.alpha
                    val coverage = (child.width.toFloat() * child.height / area).coerceIn(0f, 1f)
                    if (coverage < MIN_COVERAGE) continue
                    // A view's children draw over its own background.
                    if (depth < 3 && child is ViewGroup) visit(child, depth + 1, opacity)
                    solidColor(child.background)?.let { layers += ModalCoverLayer(coverage, it, opacity) }
                }
            }
            visit(content, 1, content.alpha)
            return modalCoverColor(layers)
        }

        private fun solidColor(drawable: Drawable?): Int? = when (drawable) {
            is ColorDrawable -> drawable.color
            is GradientDrawable -> drawable.color?.defaultColor
            is LayerDrawable -> (drawable.numberOfLayers - 1 downTo 0).firstNotNullOfOrNull {
                solidColor(drawable.getDrawable(it))
            }
            else -> null
        }

        const val MIN_COVERAGE = 0.9f
    }
}

/** A full-window candidate for the cover of an unmounted Modal: its coverage, background color and opacity. */
internal data class ModalCoverLayer(val coverage: Float, val color: Int, val opacity: Float)

/**
 * The topmost [layers] entry (they come top first) that covers the window
 * and is not transparent, as one color with its opacity folded into alpha.
 */
internal fun modalCoverColor(layers: List<ModalCoverLayer>): Int? {
    for (layer in layers) {
        if (layer.coverage < PamModalSharedTransition.MIN_COVERAGE) continue
        val alpha = ((layer.color ushr 24) * layer.opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        if (alpha == 0) continue
        return (alpha shl 24) or (layer.color and 0x00FFFFFF)
    }
    return null
}

/** Content opacity of an opening Modal at [eased] flight progress, from [floor] (a launch backdrop). */
internal fun modalSharedEnterAlpha(floor: Float, eased: Float): Float {
    val start = floor.coerceIn(0f, 1f)
    return (start + (1f - start) * eased.coerceIn(0f, 1f)).coerceIn(0f, 1f)
}

/** Content opacity of a closing Modal at [eased] flight progress, from [from]. */
internal fun modalSharedExitAlpha(from: Float, eased: Float): Float =
    (from.coerceIn(0f, 1f) * (1f - eased.coerceIn(0f, 1f))).coerceIn(0f, 1f)
