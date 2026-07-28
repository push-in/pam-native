package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

internal class PamButtonLoadingDrawable(
    sizePx: Int,
    color: Int,
) : Drawable(), Animatable {
    private val arc = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = (sizePx * 0.12f).coerceAtLeast(2f)
        this.color = color
    }
    private var rotation = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 800L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidateSelf()
        }
    }

    init {
        setBounds(0, 0, sizePx, sizePx)
    }

    override fun draw(canvas: Canvas) {
        val inset = paint.strokeWidth / 2f
        arc.set(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        canvas.save()
        canvas.rotate(rotation, bounds.exactCenterX(), bounds.exactCenterY())
        canvas.drawArc(arc, -90f, 270f, false, paint)
        canvas.restore()
    }

    fun setColor(color: Int) {
        if (paint.color == color) return
        paint.color = color
        invalidateSelf()
    }

    override fun start() {
        if (!animator.isStarted) animator.start()
    }

    override fun stop() {
        animator.cancel()
        rotation = 0f
    }

    override fun isRunning(): Boolean = animator.isRunning

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
