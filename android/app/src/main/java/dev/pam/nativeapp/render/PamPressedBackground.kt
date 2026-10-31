package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable

/** Interpolates all ARGB channels, including transparent themed surfaces. */
internal fun pressedBackgroundColor(from: Int, to: Int, fraction: Float): Int {
    val progress = fraction.coerceIn(0f, 1f)
    var result = 0
    for (shift in 24 downTo 0 step 8) {
        val start = (from ushr shift) and 255
        val end = (to ushr shift) and 255
        result = result or ((start + (end - start) * progress).toInt() shl shift)
    }
    return result
}

/** Owns only the fill: ripple, border and corner geometry remain intact. */
internal class PamPressedBackground(private val host: PamPressable) {
    private var shape: GradientDrawable? = null
    private var base = 0
    private var pressed: Int? = null
    private var color = 0
    private var animator: ValueAnimator? = null

    fun configure(shape: GradientDrawable, base: Int, pressed: Int?) {
        cancel()
        this.shape = shape
        this.base = base
        this.pressed = pressed
        color = if (host.isPressed) pressed ?: base else base
        shape.setColor(color)
    }

    fun setPressed(active: Boolean) {
        val target = pressed ?: return
        val destination = if (active) target else base
        cancel()
        if (color == destination) return
        if (!host.isShown || PamMotionPolicy.isReduced(host.context)) {
            color = destination
            shape?.setColor(color)
            return
        }
        val start = color
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            addUpdateListener {
                color = pressedBackgroundColor(start, destination, it.animatedValue as Float)
                shape?.setColor(color)
            }
            start()
        }
    }

    fun reset() {
        cancel()
        color = base
        shape?.setColor(base)
    }

    private fun cancel() {
        animator?.cancel()
        animator = null
    }
}
