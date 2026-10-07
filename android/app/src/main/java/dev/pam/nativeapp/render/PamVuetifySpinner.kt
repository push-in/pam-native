package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import kotlin.math.cos

/**
 * A plain view, not a ProgressBar: the ProgressBar constructor loads the
 * platform's indeterminate AnimatedVectorDrawable even without a style (some
 * vendors' ProgressBar always does), 15-25 ms on the UI thread for every new
 * spinner, e.g. a list's "loading older rows" cell. Only the theme's progress
 * tint is kept; accessibility still reports a ProgressBar.
 */
internal class PamVuetifySpinner(context: Context) : View(context) {
    val indeterminateDrawable: Drawable = PamVuetifySpinnerDrawable(context)

    var indeterminateTintList: ColorStateList? = null
        set(value) {
            field = value
            value?.let { indeterminateDrawable.setTint(it.getColorForState(drawableState, it.defaultColor)) }
        }

    init {
        indeterminateDrawable.callback = this
        indeterminateTintList = themeProgressTint(context)
    }

    override fun verifyDrawable(who: Drawable): Boolean =
        who === indeterminateDrawable || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        indeterminateDrawable.setBounds(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        indeterminateDrawable.draw(canvas)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // Like ProgressBar: animate while shown, stop when hidden or detached.
        indeterminateDrawable.setVisible(isVisible, false)
    }

    override fun getAccessibilityClassName(): CharSequence = ProgressBar::class.java.name
}

private val PROGRESS_TINT_ATTRS = intArrayOf(android.R.attr.colorControlActivated)

private fun themeProgressTint(context: Context): ColorStateList? {
    val values = context.obtainStyledAttributes(PROGRESS_TINT_ATTRS)
    return try {
        values.getColorStateList(0)
    } finally {
        values.recycle()
    }
}

private class PamVuetifySpinnerDrawable(
    private val context: Context,
) : Drawable(), Animatable {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 4f * density
    }
    private val arcBounds = RectF()
    private var phase = 0f
    private var running = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_400L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2f
        arcBounds.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        val easedDash = ((1f - cos(phase * Math.PI * 2.0)) / 2.0).toFloat()
        val sweep = 20f + (260f * easedDash)
        val start = -90f + (phase * 720f) - (sweep * 0.25f)
        canvas.drawArc(arcBounds, start, sweep, false, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun setTint(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun start() {
        if (running) return
        running = true
        if (PamMotionPolicy.isReduced(context)) {
            phase = 0.2f
            invalidateSelf()
            return
        }
        animator.start()
    }

    override fun stop() {
        if (!running) return
        running = false
        animator.cancel()
        phase = 0f
        invalidateSelf()
    }

    override fun isRunning(): Boolean = running

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            stop()
        } else if (restart || changed) {
            start()
        }
        return changed
    }
}
