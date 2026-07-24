package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Switch
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
internal class PamSwitch(context: Context) : Switch(context) {
    private val defaultTrackTint = trackTintList
    private val defaultThumbTint = thumbTintList
    private var trackOffColor: Int? = null
    private var trackOnColor: Int? = null

    init {
        showText = false
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

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
