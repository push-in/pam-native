package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal class PamScrollContainer(context: Context) : FrameLayout(context) {
    private var horizontal = false
    private var scrollEnabled = true
    private var showsIndicator = false
    private var fillViewport = true
    private var nestedScrollEnabled = true
    private var configuredOverScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    private var fadingEdgeLengthPx = 0
    private var persistentScrollbar = false
    private var pagingEnabled = false
    private var snapIntervalPx = 0
    private var decelerationRate = NORMAL_DECELERATION_RATE
    private var keyboardDismissMode = KEYBOARD_DISMISS_NONE
    private var requestedOffsetX = 0
    private var requestedOffsetY = 0
    private var hasRequestedOffsetX = false
    private var hasRequestedOffsetY = false
    private var offsetScheduled = false
    private var previousPrimaryMaxOffset = 0
    private var primaryMaxOffsetBeforeLastLayout = 0
    private var anchorToEnd = false
    private var initialEndAnchorApplied = false
    private var maintainVisibleContentPosition = false
    private var autoScrollToEndThresholdPx = 24
    private var scrollTargetTestId = ""
    private var scrollTargetOffsetPx = -1
    private var keyboardAvoidanceInsetPx = 0
    private var keyboardBaseContentExtentPx = 0
    private val applyOffsetRunnable = Runnable {
        offsetScheduled = false
        applyRequestedOffset()
    }
    private var viewportChanged: ((Float, Float) -> Unit)? = null
    private val content = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        setBackgroundColor(Color.TRANSPARENT)
    }
    private var activeScroll: ViewGroup = createVerticalScroll()

    init {
        clipChildren = true
        clipToPadding = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        activeScroll.setBackgroundColor(Color.TRANSPARENT)
        activeScroll.addView(content, contentLayout())
        addView(activeScroll, matchParentLayout())
        applyConfiguration()
    }

    fun insert(child: View) {
        content.addView(child)
        requestOffsetApplication()
    }

    fun setHorizontal(value: Boolean) {
        if (horizontal == value) return
        val previous = activeScroll
        val previousX = scrollXOf(previous)
        val previousY = scrollYOf(previous)
        previous.removeView(content)
        removeView(previous)
        horizontal = value
        activeScroll = if (value) createHorizontalScroll() else createVerticalScroll()
        activeScroll.setBackgroundColor(Color.TRANSPARENT)
        activeScroll.addView(content, contentLayout())
        addView(activeScroll, matchParentLayout())
        applyConfiguration()
        if (!hasRequestedOffsetX) requestedOffsetX = previousX
        if (!hasRequestedOffsetY) requestedOffsetY = previousY
        applyRequestedOffset()
    }

    fun isHorizontal(): Boolean = horizontal

    fun setScrollEnabled(value: Boolean) {
        scrollEnabled = value
        activeScroll.isEnabled = value
        if (!value) {
            (activeScroll as? ScrollView)?.fling(0)
            (activeScroll as? HorizontalScrollView)?.fling(0)
        }
        configurable().scrollingEnabled = value
    }

    fun setShowsScrollIndicator(value: Boolean) {
        showsIndicator = value
        activeScroll.isHorizontalScrollBarEnabled = value && horizontal
        activeScroll.isVerticalScrollBarEnabled = value && !horizontal
    }

    fun setFillViewport(value: Boolean) {
        fillViewport = value
        when (val scroll = activeScroll) {
            is ScrollView -> scroll.isFillViewport = value
            is HorizontalScrollView -> scroll.isFillViewport = value
        }
    }

    fun setNestedScrollEnabled(value: Boolean) {
        nestedScrollEnabled = value
        activeScroll.isNestedScrollingEnabled = value
    }

    fun setOverScrollModeValue(value: Int) {
        configuredOverScrollMode = when (value) {
            OVER_SCROLL_ALWAYS_VALUE -> OVER_SCROLL_ALWAYS
            OVER_SCROLL_NEVER_VALUE -> OVER_SCROLL_NEVER
            else -> OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        activeScroll.overScrollMode = configuredOverScrollMode
    }

    fun setFadingEdgeLength(value: Float) {
        fadingEdgeLengthPx = dp(value.coerceAtLeast(0f))
        applyFadingEdge()
    }

    fun setPersistentScrollbar(value: Boolean) {
        persistentScrollbar = value
        activeScroll.isScrollbarFadingEnabled = !value
    }

    fun setPagingEnabled(value: Boolean) {
        pagingEnabled = value
        configurable().pagingEnabled = value
    }

    fun setSnapInterval(value: Float) {
        snapIntervalPx = dp(value.coerceAtLeast(0f))
        configurable().snapIntervalPx = snapIntervalPx
    }

    fun setDecelerationRate(value: Float) {
        decelerationRate = value.coerceIn(0f, 1f)
        configurable().decelerationRate = decelerationRate
    }

    fun setKeyboardDismissMode(value: Int) {
        keyboardDismissMode = value.coerceIn(
            KEYBOARD_DISMISS_NONE,
            KEYBOARD_DISMISS_INTERACTIVE,
        )
        configurable().dismissKeyboardOnDrag =
            keyboardDismissMode != KEYBOARD_DISMISS_NONE
    }

    fun setContentOffsetX(value: Float) {
        requestedOffsetX = dp(value.coerceAtLeast(0f))
        hasRequestedOffsetX = true
        requestOffsetApplication()
    }

    fun setContentOffsetY(value: Float) {
        requestedOffsetY = dp(value.coerceAtLeast(0f))
        hasRequestedOffsetY = true
        requestOffsetApplication()
    }

    fun setAnchorToEnd(value: Boolean) {
        if (anchorToEnd == value) return
        anchorToEnd = value
        if (value) {
            initialEndAnchorApplied = false
            requestOffsetApplication()
        }
    }

    fun setMaintainVisibleContentPosition(value: Boolean) {
        maintainVisibleContentPosition = value
    }

    fun setAutoScrollToEndThreshold(value: Float) {
        autoScrollToEndThresholdPx = dp(value.coerceAtLeast(0f))
    }

    fun setScrollTargetTestId(value: String) {
        scrollTargetTestId = value
    }

    fun setScrollTargetOffset(value: Float) {
        scrollTargetOffsetPx = if (value < 0f) -1 else dp(value)
    }

    fun requestScroll() {
        activeScroll.post {
            if (!isAttachedToWindow) return@post
            if (scrollTargetTestId.isNotEmpty()) {
                val target = findDescendantByTestId(content, scrollTargetTestId)
                    ?: return@post
                val rect = Rect(0, 0, target.width, target.height)
                content.offsetDescendantRectToMyCoords(target, rect)
                scrollToPrimary(if (horizontal) rect.left else rect.top)
                return@post
            }
            if (scrollTargetOffsetPx >= 0) {
                scrollToPrimary(scrollTargetOffsetPx)
            } else {
                scrollToPrimary(primaryMaxOffset())
            }
        }
    }

    fun setKeyboardAvoidanceInset(value: Int) {
        val inset = value.coerceAtLeast(0)
        if (keyboardAvoidanceInsetPx == inset) return
        val wasNearEnd =
            primaryMaxOffset() - primaryCurrentOffset() <= autoScrollToEndThresholdPx
        if (keyboardAvoidanceInsetPx == 0 && inset > 0) {
            keyboardBaseContentExtentPx = if (horizontal) content.width else content.height
        }
        keyboardAvoidanceInsetPx = inset
        if (horizontal) {
            content.setPadding(0, 0, inset, 0)
            content.minimumWidth = if (inset > 0) {
                keyboardBaseContentExtentPx + inset
            } else {
                0
            }
        } else {
            content.setPadding(0, 0, 0, inset)
            content.minimumHeight = if (inset > 0) {
                keyboardBaseContentExtentPx + inset
            } else {
                0
            }
        }
        if (inset == 0) keyboardBaseContentExtentPx = 0
        content.requestLayout()
        if (anchorToEnd && wasNearEnd) {
            activeScroll.post {
                if (isAttachedToWindow && keyboardAvoidanceInsetPx == inset) {
                    scrollToPrimary(primaryMaxOffset())
                }
            }
        }
    }

    fun keyboardAvoidanceInsetPixels(): Int = keyboardAvoidanceInsetPx

    fun setOnViewportChanged(listener: ((Float, Float) -> Unit)?) {
        viewportChanged = listener
    }

    fun primaryOffset(x: Float, y: Float): Float = if (horizontal) x else y

    fun snapshotOffsetPixels(): Pair<Int, Int> =
        scrollXOf(activeScroll) to scrollYOf(activeScroll)

    fun restoreOffsetPixels(x: Int, y: Int) {
        if (hasRequestedOffsetX || hasRequestedOffsetY) return
        val targetOffset = if (horizontal) x else y
        val targetWasAtPreviousEnd =
            abs(targetOffset - previousPrimaryMaxOffset) <= autoScrollToEndThresholdPx ||
                abs(targetOffset - primaryMaxOffsetBeforeLastLayout) <=
                autoScrollToEndThresholdPx
        if (
            anchorToEnd &&
            targetWasAtPreviousEnd &&
            (primaryMaxOffset() - primaryCurrentOffset())
                .coerceAtLeast(0) <= autoScrollToEndThresholdPx
        ) {
            return
        }
        activeScroll.post {
            if (
                isAttachedToWindow
                && !hasRequestedOffsetX
                && !hasRequestedOffsetY
            ) {
                activeScroll.scrollTo(
                    x.coerceAtLeast(0),
                    y.coerceAtLeast(0),
                )
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyRequestedOffset()
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        val previousMax = previousPrimaryMaxOffset
        val previousOffset = primaryOffset(
            scrollXOf(activeScroll).toFloat(),
            scrollYOf(activeScroll).toFloat(),
        ).roundToInt()
        val distanceFromEnd = (previousMax - previousOffset).coerceAtLeast(0)
        super.onLayout(changed, left, top, right, bottom)
        val newMax = primaryMaxOffset()
        primaryMaxOffsetBeforeLastLayout = previousMax
        previousPrimaryMaxOffset = newMax
        when {
            anchorToEnd && !initialEndAnchorApplied -> {
                initialEndAnchorApplied = true
                scrollToPrimary(newMax)
                scheduleRequestedOffset()
            }
            anchorToEnd &&
                newMax != previousMax &&
                distanceFromEnd <= autoScrollToEndThresholdPx -> scrollToPrimary(newMax)
            maintainVisibleContentPosition && newMax > previousMax ->
                scrollToPrimary(previousOffset + newMax - previousMax)
            distanceFromEnd <= 2 && (hasRequestedOffsetX || hasRequestedOffsetY) ->
                requestOffsetApplication()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(applyOffsetRunnable)
        offsetScheduled = false
        super.onDetachedFromWindow()
    }

    private fun createVerticalScroll(): ViewGroup =
        PamVerticalScrollView(context, ::dismissKeyboard).apply {
            setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                dispatchViewport(scrollX, scrollY)
            }
        }

    private fun createHorizontalScroll(): ViewGroup =
        PamHorizontalScrollView(context, ::dismissKeyboard).apply {
            setOnScrollChangeListener { _, scrollX, scrollY, _, _ ->
                dispatchViewport(scrollX, scrollY)
            }
        }

    private fun applyConfiguration() {
        activeScroll.clipChildren = true
        activeScroll.clipToPadding = true
        activeScroll.isEnabled = scrollEnabled
        setShowsScrollIndicator(showsIndicator)
        setFillViewport(fillViewport)
        setNestedScrollEnabled(nestedScrollEnabled)
        activeScroll.overScrollMode = configuredOverScrollMode
        activeScroll.isScrollbarFadingEnabled = !persistentScrollbar
        applyFadingEdge()
        configurable().apply {
            scrollingEnabled = scrollEnabled
            pagingEnabled = this@PamScrollContainer.pagingEnabled
            snapIntervalPx = this@PamScrollContainer.snapIntervalPx
            decelerationRate = this@PamScrollContainer.decelerationRate
            dismissKeyboardOnDrag =
                keyboardDismissMode != KEYBOARD_DISMISS_NONE
        }
    }

    private fun applyFadingEdge() {
        val enabled = fadingEdgeLengthPx > 0
        activeScroll.isHorizontalFadingEdgeEnabled = enabled && horizontal
        activeScroll.isVerticalFadingEdgeEnabled = enabled && !horizontal
        if (enabled) activeScroll.setFadingEdgeLength(fadingEdgeLengthPx)
    }

    private fun applyRequestedOffset() {
        if (!isLaidOut || activeScroll.childCount == 0) {
            scheduleRequestedOffset()
            return
        }
        val x = if (hasRequestedOffsetX) requestedOffsetX else scrollXOf(activeScroll)
        val y = if (hasRequestedOffsetY) requestedOffsetY else scrollYOf(activeScroll)
        activeScroll.scrollTo(x, y)
    }

    private fun requestOffsetApplication() {
        applyRequestedOffset()
        scheduleRequestedOffset()
    }

    private fun scheduleRequestedOffset() {
        if (!isAttachedToWindow || offsetScheduled) return
        offsetScheduled = true
        post(applyOffsetRunnable)
    }

    private fun dispatchViewport(scrollX: Int, scrollY: Int) {
        val density = resources.displayMetrics.density
        viewportChanged?.invoke(scrollX / density, scrollY / density)
    }

    private fun dismissKeyboard() {
        val focused = rootView.findFocus() ?: return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private fun configurable(): ConfigurableScroll =
        activeScroll as ConfigurableScroll

    private fun matchParentLayout(): LayoutParams =
        LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

    private fun contentLayout(): ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(
            if (horizontal) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                ViewGroup.LayoutParams.MATCH_PARENT
            },
            if (horizontal) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            },
        )

    private fun scrollXOf(view: View): Int = view.scrollX.coerceAtLeast(0)
    private fun scrollYOf(view: View): Int = view.scrollY.coerceAtLeast(0)

    private fun primaryMaxOffset(): Int =
        if (horizontal) {
            (content.width - activeScroll.width).coerceAtLeast(0)
        } else {
            (content.height - activeScroll.height).coerceAtLeast(0)
        }

    private fun primaryCurrentOffset(): Int =
        if (horizontal) scrollXOf(activeScroll) else scrollYOf(activeScroll)

    private fun scrollToPrimary(offset: Int) {
        if (horizontal) {
            activeScroll.scrollTo(offset.coerceAtLeast(0), scrollYOf(activeScroll))
        } else {
            activeScroll.scrollTo(scrollXOf(activeScroll), offset.coerceAtLeast(0))
        }
    }

    private fun findDescendantByTestId(root: View, testId: String): View? {
        if (root.transitionName == testId) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findDescendantByTestId(root.getChildAt(index), testId)?.let {
                return it
            }
        }
        return null
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private interface ConfigurableScroll {
        var scrollingEnabled: Boolean
        var pagingEnabled: Boolean
        var snapIntervalPx: Int
        var decelerationRate: Float
        var dismissKeyboardOnDrag: Boolean
    }

    private class PamVerticalScrollView(
        context: Context,
        private val dismissKeyboard: () -> Unit,
    ) : ScrollView(context), ConfigurableScroll {
        override var scrollingEnabled = true
        override var pagingEnabled = false
        override var snapIntervalPx = 0
        override var decelerationRate = NORMAL_DECELERATION_RATE
        override var dismissKeyboardOnDrag = false
        private var flingStarted = false
        private val touch = ScrollTouchTracker(context)

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (!scrollingEnabled) return false
            val intercepted = super.onInterceptTouchEvent(event)
            if (
                intercepted &&
                event.actionMasked == MotionEvent.ACTION_MOVE &&
                dismissKeyboardOnDrag
            ) {
                dismissKeyboard()
            }
            return intercepted
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!scrollingEnabled) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) flingStarted = false
            touch.observe(event)
            val handled = super.onTouchEvent(event)
            if (handled && event.actionMasked == MotionEvent.ACTION_UP) {
                if (!touch.moved) performClick()
                if (!flingStarted) post(::snapToNearest)
            }
            touch.finish(event)
            return handled
        }

        override fun performClick(): Boolean = super.performClick()

        override fun fling(velocityY: Int) {
            flingStarted = true
            val velocity = adjustedVelocity(velocityY, decelerationRate)
            if (snapExtent() > 0) {
                snapWithVelocity(scrollY, velocity, snapExtent(), maxScroll()) {
                    smoothScrollTo(0, it)
                }
            } else {
                super.fling(velocity)
            }
        }

        private fun snapToNearest() {
            val extent = snapExtent()
            if (extent > 0) {
                smoothScrollTo(
                    0,
                    nearestSnap(scrollY, extent).coerceIn(0, maxScroll()),
                )
            }
        }

        private fun snapExtent(): Int =
            when {
                snapIntervalPx > 0 -> snapIntervalPx
                pagingEnabled -> height - paddingTop - paddingBottom
                else -> 0
            }.coerceAtLeast(0)

        private fun maxScroll(): Int =
            max(
                0,
                (getChildAt(0)?.height ?: 0) -
                    height +
                    paddingTop +
                    paddingBottom,
            )
    }

    private class PamHorizontalScrollView(
        context: Context,
        private val dismissKeyboard: () -> Unit,
    ) : HorizontalScrollView(context), ConfigurableScroll {
        override var scrollingEnabled = true
        override var pagingEnabled = false
        override var snapIntervalPx = 0
        override var decelerationRate = NORMAL_DECELERATION_RATE
        override var dismissKeyboardOnDrag = false
        private var flingStarted = false
        private val touch = ScrollTouchTracker(context)

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (!scrollingEnabled) return false
            val intercepted = super.onInterceptTouchEvent(event)
            if (
                intercepted &&
                event.actionMasked == MotionEvent.ACTION_MOVE &&
                dismissKeyboardOnDrag
            ) {
                dismissKeyboard()
            }
            return intercepted
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!scrollingEnabled) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) flingStarted = false
            touch.observe(event)
            val handled = super.onTouchEvent(event)
            if (handled && event.actionMasked == MotionEvent.ACTION_UP) {
                if (!touch.moved) performClick()
                if (!flingStarted) post(::snapToNearest)
            }
            touch.finish(event)
            return handled
        }

        override fun performClick(): Boolean = super.performClick()

        override fun fling(velocityX: Int) {
            flingStarted = true
            val velocity = adjustedVelocity(velocityX, decelerationRate)
            if (snapExtent() > 0) {
                snapWithVelocity(scrollX, velocity, snapExtent(), maxScroll()) {
                    smoothScrollTo(it, 0)
                }
            } else {
                super.fling(velocity)
            }
        }

        private fun snapToNearest() {
            val extent = snapExtent()
            if (extent > 0) {
                smoothScrollTo(
                    nearestSnap(scrollX, extent).coerceIn(0, maxScroll()),
                    0,
                )
            }
        }

        private fun snapExtent(): Int =
            when {
                snapIntervalPx > 0 -> snapIntervalPx
                pagingEnabled -> width - paddingLeft - paddingRight
                else -> 0
            }.coerceAtLeast(0)

        private fun maxScroll(): Int =
            max(
                0,
                (getChildAt(0)?.width ?: 0) -
                    width +
                    paddingLeft +
                    paddingRight,
            )
    }

    private class ScrollTouchTracker(context: Context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        var moved = false
            private set

        fun observe(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (
                        abs(event.x - downX) > slop ||
                        abs(event.y - downY) > slop
                    ) {
                        moved = true
                    }
                }
            }
        }

        fun finish(event: MotionEvent) {
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                moved = false
            }
        }
    }

    private companion object {
        const val OVER_SCROLL_ALWAYS_VALUE = 2
        const val OVER_SCROLL_NEVER_VALUE = 3
        const val KEYBOARD_DISMISS_NONE = 1
        const val KEYBOARD_DISMISS_INTERACTIVE = 3
        const val NORMAL_DECELERATION_RATE = 0.985f

        fun adjustedVelocity(velocity: Int, rate: Float): Int =
            (velocity * (rate / NORMAL_DECELERATION_RATE)).roundToInt()

        fun nearestSnap(position: Int, extent: Int): Int =
            (position.toFloat() / extent).roundToInt() * extent

        fun snapWithVelocity(
            position: Int,
            velocity: Int,
            extent: Int,
            maxScroll: Int,
            scroll: (Int) -> Unit,
        ) {
            val projected = position + velocity / 4
            scroll(nearestSnap(projected, extent).coerceIn(0, maxScroll))
        }
    }
}
