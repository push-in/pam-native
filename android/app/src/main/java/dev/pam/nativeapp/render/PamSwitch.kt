package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.Switch
import kotlin.math.min
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
@SuppressLint("UseSwitchCompatOrMaterialCode")
internal class PamSwitch(context: Context) : Switch(context) {
    private val defaultTrackTint = trackTintList
    private val defaultThumbTint = thumbTintList
    private var trackOffColor: Int? = null
    private var trackOnColor: Int? = null

    init {
        showText = false
        splitTrack = false
        switchMinWidth = dp(36)
        minimumWidth = dp(40)
        minimumHeight = dp(40)
        // React Native's Switch is an AppCompat SwitchCompat: a 20 dp thumb
        // inside a 27 dp drawable (3.5 dp shadow padding) over a 14 dp tall
        // track inset 4 dp from the 40 dp switch area. The platform Switch
        // stretches the track drawable over the whole switch height, so a
        // plain filled track was 20 dp thick.
        trackDrawable = PamSwitchTrackDrawable(
            intrinsicWidthPx = dp(23.5f),
            intrinsicHeightPx = dp(16),
            barHeightPx = dp(14),
            horizontalInsetPx = dp(4),
        )
        thumbDrawable = PamSwitchThumbDrawable(diameterPx = dp(20), paddingPx = dp(3.5f))
        trackTintList = defaultTrackTint
        thumbTintList = defaultThumbTint
        elevation = dp(2).toFloat()
    }

    fun setTrackOffColor(color: Int?) {
        trackOffColor = color
        applyTrackTint()
    }

    fun setTrackOnColor(color: Int?) {
        trackOnColor = color
        applyTrackTint()
    }

    fun setThumbColor(color: Int?) {
        thumbTintList = color?.let(::thumbColors) ?: defaultThumbTint
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            resolvePamSwitchMeasuredExtent(
                View.MeasureSpec.getMode(widthMeasureSpec),
                View.MeasureSpec.getSize(widthMeasureSpec),
                dp(46.5f),
            ),
            resolvePamSwitchMeasuredExtent(
                View.MeasureSpec.getMode(heightMeasureSpec),
                View.MeasureSpec.getSize(heightMeasureSpec),
                dp(27f),
            ),
        )
    }

    private fun applyTrackTint() {
        if (trackOffColor == null && trackOnColor == null) {
            trackTintList = defaultTrackTint
            return
        }
        val off = trackOffColor ?: defaultTrackTint.colorFor(false)
        val on = trackOnColor ?: defaultTrackTint.colorFor(true)
        trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(
                withAlpha(on, DISABLED_ALPHA),
                withAlpha(off, DISABLED_ALPHA),
                on,
                off,
            ),
        )
    }

    private fun thumbColors(color: Int): ColorStateList =
        ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(),
            ),
            intArrayOf(withAlpha(color, DISABLED_ALPHA), color),
        )

    private fun ColorStateList?.colorFor(checked: Boolean): Int {
        val fallback = this?.defaultColor ?: Color.GRAY
        return this?.getColorForState(
            if (checked) {
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
            } else {
                intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked)
            },
            fallback,
        ) ?: fallback
    }

    private fun withAlpha(color: Int, multiplier: Float): Int =
        Color.argb(
            (Color.alpha(color) * multiplier).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}

internal fun resolvePamSwitchMeasuredExtent(
    mode: Int,
    available: Int,
    preferred: Int,
): Int = when (mode) {
    View.MeasureSpec.EXACTLY -> available
    View.MeasureSpec.AT_MOST -> min(available, preferred)
    else -> preferred
}

/**
 * AppCompat's `abc_switch_track_mtrl_alpha` 9-patch: a 14 dp pill, 4 dp in
 * from each horizontal edge, that stays 14 dp tall and vertically centred
 * however tall the switch draws it, with no padding (the thumb travels over
 * the whole switch width).
 */
internal class PamSwitchTrackDrawable(
    private val intrinsicWidthPx: Int,
    private val intrinsicHeightPx: Int,
    private val barHeightPx: Int,
    private val horizontalInsetPx: Int,
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bar = android.graphics.RectF()
    private var tint: ColorStateList? = null
    private var alphaMultiplier = 255

    override fun draw(canvas: android.graphics.Canvas) {
        val bounds = bounds
        val height = min(barHeightPx, bounds.height()).toFloat()
        val top = bounds.exactCenterY() - height / 2f
        bar.set(
            (bounds.left + horizontalInsetPx).toFloat(),
            top,
            (bounds.right - horizontalInsetPx).toFloat(),
            top + height,
        )
        if (bar.width() <= 0f) return
        val color = tint?.getColorForState(state, tint?.defaultColor ?: Color.WHITE) ?: Color.WHITE
        paint.color = color
        paint.alpha = Color.alpha(color) * alphaMultiplier / 255
        canvas.drawRoundRect(bar, height / 2f, height / 2f, paint)
    }

    override fun getIntrinsicWidth(): Int = intrinsicWidthPx

    override fun getIntrinsicHeight(): Int = intrinsicHeightPx

    override fun getPadding(padding: android.graphics.Rect): Boolean {
        padding.set(0, 0, 0, 0)
        return false
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return tint?.isStateful == true
    }

    override fun setAlpha(alpha: Int) {
        alphaMultiplier = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * AppCompat's switch thumb: a 20 dp circle inside a 27 dp drawable whose
 * 3.5 dp padding (the shadow area of the 9-patch) lets it overhang the track
 * ends. Unlike an InsetDrawable it reports no optical insets, which would
 * shrink the track and widen the switch.
 */
internal class PamSwitchThumbDrawable(
    private val diameterPx: Int,
    private val paddingPx: Int,
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var tint: ColorStateList? = null
    private var alphaMultiplier = 255

    override fun draw(canvas: android.graphics.Canvas) {
        val bounds = bounds
        val radius = min(diameterPx.toFloat(), min(bounds.width(), bounds.height()).toFloat()) / 2f
        if (radius <= 0f) return
        val color = tint?.getColorForState(state, tint?.defaultColor ?: Color.WHITE) ?: Color.WHITE
        paint.color = color
        paint.alpha = Color.alpha(color) * alphaMultiplier / 255
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
    }

    override fun getIntrinsicWidth(): Int = diameterPx + 2 * paddingPx

    override fun getIntrinsicHeight(): Int = diameterPx + 2 * paddingPx

    override fun getPadding(padding: android.graphics.Rect): Boolean {
        padding.set(paddingPx, paddingPx, paddingPx, paddingPx)
        return true
    }

    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }

    override fun isStateful(): Boolean = tint?.isStateful == true

    override fun onStateChange(state: IntArray): Boolean {
        invalidateSelf()
        return tint?.isStateful == true
    }

    override fun setAlpha(alpha: Int) {
        alphaMultiplier = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
