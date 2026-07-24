package dev.pam.nativeapp.render

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal class PamRefreshContainer(context: Context) : FrameLayout(context) {
    private val indicatorHolder = FrameLayout(context)
    private val indicator = ProgressBar(context).apply {
        isIndeterminate = true
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val refreshThreshold = dp(64f)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var refreshing = false
    private var progressOffset = 0
    private var indicatorSize = INDICATOR_DEFAULT
    private var colors = intArrayOf(resolveColor(android.R.attr.colorAccent, Color.DKGRAY))
    private var colorAnimator: ValueAnimator? = null
    private var refresh: (() -> Unit)? = null

    init {
        indicatorHolder.apply {
            visibility = View.GONE
            elevation = dp(6f).toFloat()
            background = indicatorBackground(resolveColor(android.R.attr.colorBackground, Color.WHITE))
            addView(
                indicator,
                LayoutParams(dp(28f), dp(28f), Gravity.CENTER),
            )
        }
        addView(
            indicatorHolder,
            LayoutParams(dp(40f), dp(40f), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        updateIndicatorColor(colors.first())
    }

    fun insert(view: View, index: Int) {
        addView(view, (index + 1).coerceIn(1, childCount))
    }

    fun setOnRefresh(listener: (() -> Unit)?) {
        refresh = listener
    }

    fun setRefreshing(value: Boolean) {
        refreshing = value
        dragging = false
        if (value) {
            indicatorHolder.alpha = 1f
            indicatorHolder.translationY = 0f
            indicatorHolder.visibility = View.VISIBLE
            startColorAnimation()
        } else {
            indicatorHolder.visibility = View.GONE
            indicatorHolder.translationY = 0f
            stopColorAnimation()
        }
    }

    fun setColors(encoded: String?) {
        val decoded = encoded
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull()?.toInt() }
            ?.toIntArray()
            ?.takeIf { it.isNotEmpty() }
            ?: intArrayOf(resolveColor(android.R.attr.colorAccent, Color.DKGRAY))
        colors = decoded
        updateIndicatorColor(decoded.first())
        if (refreshing) startColorAnimation()
    }

    fun setProgressBackgroundColor(color: Int?) {
        val resolved = color
            ?: resolveColor(android.R.attr.colorBackground, Color.WHITE)
        indicatorHolder.background = indicatorBackground(resolved)
    }

    fun setProgressViewOffset(offset: Float) {
        progressOffset = dp(offset)
        updateIndicatorLayout()
    }

    fun setIndicatorSize(size: Int) {
        indicatorSize = size
        updateIndicatorLayout()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled && !refreshing) {
            setRefreshing(false)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || refreshing || refresh == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceX = abs(event.x - downX)
                val distanceY = event.y - downY
                if (
                    distanceY > touchSlop &&
                    distanceY > distanceX &&
                    !canContentScrollUp()
                ) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    showDrag(distanceY)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> dragging = false
        }

        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || refresh == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val distance = (event.y - downY).coerceAtLeast(0f)
                if (!dragging && distance > touchSlop && !canContentScrollUp()) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) showDrag(distance)
                return dragging
            }
            MotionEvent.ACTION_UP -> {
                val distance = (event.y - downY).coerceAtLeast(0f)
                val shouldRefresh = dragging && distance >= refreshThreshold
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (shouldRefresh) {
                    setRefreshing(true)
                    refresh?.invoke()
                } else {
                    setRefreshing(false)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                setRefreshing(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopColorAnimation()
        super.onDetachedFromWindow()
    }

    private fun canContentScrollUp(): Boolean {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== indicatorHolder) return child.canScrollVertically(-1)
        }

        return false
    }

    private fun showDrag(distance: Float) {
        val progress = min(1f, distance / refreshThreshold)
        indicatorHolder.visibility = View.VISIBLE
        indicatorHolder.alpha = progress
        indicatorHolder.translationY = min(distance * 0.5f, refreshThreshold.toFloat())
        updateIndicatorColor(colors.first())
    }

    private fun updateIndicatorLayout() {
        val large = indicatorSize == INDICATOR_LARGE
        val holderSize = dp(if (large) 56f else 40f)
        val progressSize = dp(if (large) 40f else 28f)
        indicatorHolder.layoutParams = LayoutParams(
            holderSize,
            holderSize,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = progressOffset
        }
        indicator.layoutParams = LayoutParams(progressSize, progressSize, Gravity.CENTER)
    }

    private fun indicatorBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun startColorAnimation() {
        stopColorAnimation()
        if (colors.size == 1) {
            updateIndicatorColor(colors.first())
            return
        }
        colorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            *colors.toTypedArray(),
        ).apply {
            duration = colors.size * 700L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                updateIndicatorColor(animation.animatedValue as Int)
            }
            start()
        }
    }

    private fun stopColorAnimation() {
        colorAnimator?.cancel()
        colorAnimator = null
    }

    private fun updateIndicatorColor(color: Int) {
        indicator.indeterminateTintList = ColorStateList.valueOf(color)
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) {
            value.data
        } else {
            fallback
        }
    }

    private fun dp(value: Float): Int =
        max(0, (value * resources.displayMetrics.density + 0.5f).toInt())

    private companion object {
        const val INDICATOR_DEFAULT = 1
        const val INDICATOR_LARGE = 2
    }
}
