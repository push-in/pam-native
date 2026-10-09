package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
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
        // React Native's Switch is an AppCompat SwitchCompat. PAM draws it
        // with AppCompat's own 9-patches (res/drawable-*/pam_switch_*): a 14 dp
        // track pill and a 20 dp thumb disc with its baked drop shadow, whose
        // 3.5 dp optical insets move the switch area in from the right edge
        // and shrink the track exactly like SwitchCompat (Switch applies them
        // the same way). RN colors both with MULTIPLY filters, which keep the
        // shadow black; SRC_IN tinting would paint it in the thumb color.
        trackDrawable = context.getDrawable(dev.pam.nativeapp.R.drawable.pam_switch_track)
        thumbDrawable = context.getDrawable(dev.pam.nativeapp.R.drawable.pam_switch_thumb)
        trackTintMode = PorterDuff.Mode.MULTIPLY
        thumbTintMode = PorterDuff.Mode.MULTIPLY
        trackTintList = defaultTrackTint
        thumbTintList = defaultThumbTint
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
