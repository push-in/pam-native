package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * RN `RefreshControl` on Android: the platform SwipeRefreshLayout (Material
 * spinner, nested-scroll aware) around one scrollable child. The pull only
 * starts when the content is already at its top; the controlled `refreshing`
 * value always wins, so `refreshing=false` ends the spinner even when the
 * refresh handler never flipped it (RN re-applies the controlled value after
 * `onRefresh`).
 */
internal class PamRefreshContainer(context: Context) : SwipeRefreshLayout(context) {
    private var refresh: (() -> Unit)? = null
    private var colors: IntArray? = null
    private var tint: Int? = null
    private var controlled = false
    private var enabledByProps = true
    private var progressOffsetPx = 0
    private var userTriggered = false
    private var laidOut = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var horizontalGesture = false
    private val syncControlled = Runnable {
        if (userTriggered) {
            userTriggered = false
            isRefreshing = controlled
        }
    }

    init {
        updateEnabled()
        setOnChildScrollUpCallback { _, child -> canContentScrollUp(child) }
        setOnRefreshListener {
            val listener = refresh
            if (listener == null) {
                isRefreshing = controlled
                return@setOnRefreshListener
            }
            userTriggered = true
            listener()
            // A handler that leaves `refreshing` unchanged sends no update:
            // fall back to the controlled value after the round trip.
            removeCallbacks(syncControlled)
            postDelayed(syncControlled, CONTROLLED_SYNC_MS)
        }
    }

    fun insert(view: View, index: Int) {
        // The circle indicator is child 0; the scrollable target follows.
        addView(view, (index + 1).coerceIn(1, childCount))
    }

    fun setOnRefresh(listener: (() -> Unit)?) {
        refresh = listener
        updateEnabled()
    }

    fun setControlledRefreshing(value: Boolean) {
        controlled = value
        userTriggered = false
        removeCallbacks(syncControlled)
        // SwipeRefreshLayout cannot show its spinner before the first layout.
        if (laidOut && isRefreshing != value) isRefreshing = value
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!laidOut) {
            laidOut = true
            if (controlled) isRefreshing = true
        }
    }

    /**
     * RN parity: a gesture that moves horizontally first belongs to a nested
     * carousel/pager and never starts a pull.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                horizontalGesture = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (horizontalGesture || abs(event.x - downX) > touchSlop) {
                    horizontalGesture = true
                    return false
                }
            }
        }
        if (!super.onInterceptTouchEvent(event)) return false
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    /** RN `enabled`: a disabled control never starts a pull. */
    override fun setEnabled(enabled: Boolean) {
        enabledByProps = enabled
        updateEnabled()
    }

    /** RN `colors` (Android): the spinner cycles through them. */
    fun setColors(encoded: String?) {
        colors = encoded
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull()?.toInt() }
            ?.takeIf { it.isNotEmpty() }
            ?.toIntArray()
        applyColors()
    }

    /** RN `tintColor` (iOS): used on Android too when no `colors` are set. */
    fun setTint(color: Int?) {
        tint = color
        applyColors()
    }

    private fun applyColors() {
        val scheme = colors
            ?: tint?.let { intArrayOf(it) }
            ?: intArrayOf(resolveColor(android.R.attr.colorAccent, Color.DKGRAY))
        setColorSchemeColors(*scheme)
    }

    /**
     * RN `progressBackgroundColor` as ARGB. Not named `setProgressBackgroundColor`:
     * a non-null Int resolved to SwipeRefreshLayout's deprecated
     * `setProgressBackgroundColor(@ColorRes int)` and threw
     * `Resources.NotFoundException: Resource ID #0xff1d231e`.
     */
    fun setProgressBackgroundArgb(color: Int?) {
        setProgressBackgroundColorSchemeColor(
            color ?: resolveColor(android.R.attr.colorBackground, Color.WHITE),
        )
    }

    /** RN `progressViewOffset`: the spinner's resting top (below a translucent header). */
    fun setProgressViewOffset(offset: Float) {
        progressOffsetPx = dp(offset)
        val circle = progressCircleDiameter
        setProgressViewOffset(
            false,
            progressOffsetPx - circle,
            progressOffsetPx + dp(DEFAULT_RESTING_OFFSET_DP),
        )
    }

    fun setIndicatorSize(size: Int) {
        setSize(if (size == INDICATOR_LARGE) LARGE else DEFAULT)
        setProgressViewOffset(progressOffsetPx / resources.displayMetrics.density)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(syncControlled)
        super.onDetachedFromWindow()
    }

    private fun updateEnabled() {
        super.setEnabled(enabledByProps && refresh != null)
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun dp(value: Float): Int =
        max(0, (value * resources.displayMetrics.density + 0.5f).toInt())

    internal companion object {
        const val INDICATOR_LARGE = 2
        const val CONTROLLED_SYNC_MS = 600L
        const val DEFAULT_RESTING_OFFSET_DP = 64f

        /**
         * Whether the pull must defer to the content: a PAM ScrollView hosts
         * its real scroller inside a wrapper, so ask it instead of the wrapper.
         */
        fun canContentScrollUp(child: View?): Boolean = when (child) {
            null -> false
            is PamScrollContainer -> child.canContentScrollUp()
            else -> child.canScrollVertically(-1)
        }
    }
}
