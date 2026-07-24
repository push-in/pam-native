package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Animatable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.max

internal class PamActivityIndicator(context: Context) : FrameLayout(context) {
    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
    }
    private val defaultTint = indicator.indeterminateTintList
    private var requestedVisible = true
    private var animating = true
    private var hidesWhenStopped = true
    private var size = DEFAULT_SIZE

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        indicator.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        addView(indicator)
        applySize()
        applyState()
    }

    fun setRequestedVisible(value: Boolean) {
        requestedVisible = value
        applyState()
    }

    fun setAnimating(value: Boolean) {
        animating = value
        applyState()
    }

    fun setHidesWhenStopped(value: Boolean) {
        hidesWhenStopped = value
        applyState()
    }

    fun setSize(value: Float) {
        size = value.coerceAtLeast(1f)
        applySize()
    }

    fun setColor(color: Int?) {
        indicator.indeterminateTintList =
            color?.let(ColorStateList::valueOf) ?: defaultTint
    }

    fun setHostAccessibility(enabled: Boolean) {
        indicator.importantForAccessibility = if (enabled) {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun applyState() {
        val drawable = indicator.indeterminateDrawable
        if (animating && requestedVisible) {
            (drawable as? Animatable)?.start()
        } else {
            (drawable as? Animatable)?.stop()
        }
        visibility = if (
            requestedVisible &&
            (animating || !hidesWhenStopped)
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applySize() {
        val pixels = dp(size)
        indicator.layoutParams = LayoutParams(pixels, pixels, Gravity.CENTER)
    }

    private fun dp(value: Float): Int =
        max(1, (value * resources.displayMetrics.density + 0.5f).toInt())

    private companion object {
        const val DEFAULT_SIZE = 20f
    }
}
