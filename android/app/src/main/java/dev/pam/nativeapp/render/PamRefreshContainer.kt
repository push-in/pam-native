package dev.pam.nativeapp.render

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import kotlin.math.max

internal class PamRefreshContainer(context: Context) : FrameLayout(context) {
    private val indicator = ProgressBar(context).apply {
        visibility = View.GONE
    }
    private var downY = 0f
    private var refreshing = false
    private var refresh: (() -> Unit)? = null

    init {
        addView(
            indicator,
            LayoutParams(dp(36f), dp(36f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(8f)
            },
        )
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = event.y
                MotionEvent.ACTION_UP -> {
                    val content = childCount.takeIf { it > 1 }?.let { getChildAt(1) }
                    val distance = event.y - downY
                    if (!refreshing && distance >= dp(64f) && content?.canScrollVertically(-1) != true) {
                        setRefreshing(true)
                        refresh?.invoke()
                    } else {
                        performClick()
                    }
                }
            }
            false
        }
    }

    fun insert(view: View, index: Int) {
        addView(view, (index + 1).coerceIn(1, childCount))
    }

    fun setOnRefresh(listener: (() -> Unit)?) {
        refresh = listener
    }

    fun setRefreshing(value: Boolean) {
        refreshing = value
        indicator.visibility = if (value) View.VISIBLE else View.GONE
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Int =
        max(0, (value * resources.displayMetrics.density + 0.5f).toInt())
}
