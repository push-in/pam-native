package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.abs

internal class PamDrawerLayout(context: Context) : FrameLayout(context) {
    private var open = false
    private var drawerType = TYPE_FRONT
    private var drawerPosition = POSITION_AUTOMATIC
    private var drawerWidthDp = 256f
    private var overlayColor = 0x33000000
    private var swipeEnabled = true
    private var swipeEdgeWidthDp = 32f
    private var swipeMinDistanceDp = 56f
    private var keyboardDismissMode = KEYBOARD_ON_DRAG
    private var permanentBreakpointDp = 840f
    private var hideStatusBarOnOpen = false
    private var statusBarAnimation = 1
    private var downX = 0f
    private var gestureStartProgress = 0f
    private var tracking = false
    private var progress = 0f
    private var onOpen: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var navigationInsetBottom = 0
    private var insetDrawer: View? = null
    private var drawerBaseBottomPadding = 0
    private var insetViewport: View? = null
    private var viewportBaseBottomPadding = 0

    init {
        clipChildren = false
        setWillNotDraw(false)
        setOnApplyWindowInsetsListener { _, insets ->
            val nextBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (navigationInsetBottom != nextBottom) {
                navigationInsetBottom = nextBottom
                if (childCount > 1) {
                    enforceDrawerViewport(getChildAt(1))
                }
            }
            insets
        }
    }

    fun insert(view: View, index: Int) {
        addView(view, index.coerceIn(0, childCount))
        if (childCount > 1) {
            enforceDrawerViewport(getChildAt(1))
        }
        updateDrawer(animated = false)
    }

    fun setOpen(value: Boolean, animated: Boolean = true) {
        val permanent = resolvedType() == TYPE_PERMANENT
        val next = value || permanent
        if (open == next && animated) {
            updateDrawer(true)
            return
        }
        val changed = open != next
        open = next
        updateStatusBar()
        updateDrawer(animated)
        if (changed) {
            if (open) onOpen?.invoke() else onClose?.invoke()
        }
    }

    fun setDrawerType(value: Int) {
        drawerType = value.coerceIn(TYPE_FRONT, TYPE_PERMANENT)
        updateDrawer(false)
    }

    fun setDrawerPosition(value: Int) {
        drawerPosition = value.coerceIn(POSITION_AUTOMATIC, POSITION_RIGHT)
        updateDrawer(false)
    }

    fun setDrawerWidth(value: Float) {
        drawerWidthDp = value.coerceIn(200f, 640f)
        requestLayout()
    }

    fun setOverlayColor(value: Int) {
        overlayColor = value
        invalidate()
    }

    fun setSwipeEnabled(value: Boolean) {
        swipeEnabled = value
    }

    fun setSwipeEdgeWidth(value: Float) {
        swipeEdgeWidthDp = value.coerceIn(0f, 256f)
    }

    fun setSwipeMinDistance(value: Float) {
        swipeMinDistanceDp = value.coerceIn(1f, 512f)
    }

    fun setKeyboardDismissMode(value: Int) {
        keyboardDismissMode = value.coerceIn(1, 2)
    }

    fun setPermanentBreakpoint(value: Float) {
        permanentBreakpointDp = value.coerceAtLeast(0f)
        updateDrawer(false)
    }

    fun setHideStatusBarOnOpen(value: Boolean) {
        hideStatusBarOnOpen = value
        updateStatusBar()
    }

    fun setStatusBarAnimation(value: Int) {
        statusBarAnimation = value.coerceIn(1, 3)
    }

    fun setCallbacks(opened: (() -> Unit)?, closed: (() -> Unit)?) {
        onOpen = opened
        onClose = closed
    }

    override fun dispatchDraw(canvas: Canvas) {
        val content = getChildAt(0)
        val drawer = getChildAt(1)
        if (content == null || drawer == null) {
            super.dispatchDraw(canvas)
            return
        }
        val drawingTime = drawingTime
        if (resolvedType() == TYPE_BACK) {
            drawChild(canvas, drawer, drawingTime)
            drawChild(canvas, content, drawingTime)
        } else {
            drawChild(canvas, content, drawingTime)
            if (progress > 0f && resolvedType() != TYPE_PERMANENT) {
                overlayPaint.color = overlayColor
                overlayPaint.alpha = ((overlayColor ushr 24) * progress).toInt()
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            }
            drawChild(canvas, drawer, drawingTime)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val drawer = getChildAt(1)
        drawer?.let {
            enforceDrawerViewport(it)
        }
        updateDrawer(animated = false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        val handled = handleTouch(event)
        if (
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            open &&
            !outsideDrawer(event.x)
        ) {
            return false
        }
        return handled || super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        handleTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return true
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (!swipeEnabled || resolvedType() == TYPE_PERMANENT) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                gestureStartProgress = progress
                val edge = dp(swipeEdgeWidthDp)
                tracking = if (open) {
                    outsideDrawer(event.x)
                } else if (isRight()) {
                    event.x >= width - edge
                } else {
                    event.x <= edge
                }
                if (tracking && keyboardDismissMode == KEYBOARD_ON_DRAG) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(windowToken, 0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val directed = if (isRight()) {
                    downX - event.x
                } else {
                    event.x - downX
                }
                val next = (
                    gestureStartProgress + directed / drawerWidthPx().coerceAtLeast(1f)
                    ).coerceIn(0f, 1f)
                applyProgress(next)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val delta = event.x - downX
                tracking = false
                val directed = if (isRight()) -delta else delta
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    setOpen(open)
                } else if (open && outsideDrawer(event.x)) {
                    setOpen(false)
                } else if (abs(delta) >= dp(swipeMinDistanceDp)) {
                    setOpen(directed > 0f)
                } else {
                    setOpen(progress >= 0.5f)
                }
            }
        }
        return tracking
    }

    private fun updateDrawer(animated: Boolean) {
        val content = getChildAt(0) ?: return
        val drawer = getChildAt(1) ?: return
        val type = resolvedType()
        if (type == TYPE_PERMANENT) open = true
        val width = drawerWidthPx()
        val direction = if (isRight()) -1f else 1f
        val openX = if (isRight()) this.width.toFloat() - width else 0f
        val closedX = if (isRight()) this.width.toFloat() else -width
        val drawerTarget = when (type) {
            TYPE_BACK, TYPE_PERMANENT -> openX
            TYPE_SLIDE -> if (open) openX else openX + (closedX - openX) * 0.35f
            else -> if (open) openX else closedX
        }
        val contentTarget = when (type) {
            TYPE_BACK, TYPE_SLIDE -> if (open) direction * width else 0f
            TYPE_PERMANENT -> direction * width
            else -> 0f
        }
        val targetProgress = if (open && type != TYPE_PERMANENT) 1f else 0f
        if (!animated || !ValueAnimator.areAnimatorsEnabled()) {
            drawer.translationX = drawerTarget
            content.translationX = contentTarget
            progress = targetProgress
            invalidate()
            return
        }
        drawer.animate().translationX(drawerTarget).setDuration(200).start()
        content.animate().translationX(contentTarget).setDuration(200).start()
        ValueAnimator.ofFloat(progress, targetProgress).apply {
            duration = 200
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun applyProgress(value: Float) {
        val content = getChildAt(0) ?: return
        val drawer = getChildAt(1) ?: return
        val type = resolvedType()
        val width = drawerWidthPx()
        val direction = if (isRight()) -1f else 1f
        val openX = if (isRight()) this.width.toFloat() - width else 0f
        val closedX = if (isRight()) this.width.toFloat() else -width
        val drawerStart = when (type) {
            TYPE_BACK, TYPE_PERMANENT -> openX
            TYPE_SLIDE -> openX + (closedX - openX) * 0.35f
            else -> closedX
        }
        progress = value.coerceIn(0f, 1f)
        drawer.translationX = drawerStart + (openX - drawerStart) * progress
        content.translationX = when (type) {
            TYPE_BACK, TYPE_SLIDE -> direction * width * progress
            TYPE_PERMANENT -> direction * width
            else -> 0f
        }
        invalidate()
    }

    private fun resolvedType(): Int =
        if (permanentBreakpointDp > 0f && width / resources.displayMetrics.density >= permanentBreakpointDp) {
            TYPE_PERMANENT
        } else {
            drawerType
        }

    private fun isRight(): Boolean =
        drawerPosition == POSITION_RIGHT ||
            (drawerPosition == POSITION_AUTOMATIC && layoutDirection == View.LAYOUT_DIRECTION_RTL)

    private fun outsideDrawer(x: Float): Boolean =
        if (isRight()) x < width - drawerWidthPx() else x > drawerWidthPx()

    private fun drawerWidthPx(): Float = dp(drawerWidthDp).coerceAtMost(width.toFloat())
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun enforceDrawerViewport(drawer: View) {
        if (insetDrawer !== drawer) {
            insetDrawer = drawer
            drawerBaseBottomPadding = drawer.paddingBottom
        }
        drawer.setPadding(
            drawer.paddingLeft,
            drawer.paddingTop,
            drawer.paddingRight,
            maxOf(drawerBaseBottomPadding, navigationInsetBottom),
        )
        drawer.layoutParams = (drawer.layoutParams ?: LayoutParams(
            drawerWidthPx().toInt(),
            ViewGroup.LayoutParams.MATCH_PARENT,
        )).apply {
            width = drawerWidthPx().toInt()
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        val viewport = (drawer as? ViewGroup)?.getChildAt(0) ?: return
        if (insetViewport !== viewport) {
            insetViewport = viewport
            viewportBaseBottomPadding = viewport.paddingBottom
        }
        viewport.setPadding(
            viewport.paddingLeft,
            viewport.paddingTop,
            viewport.paddingRight,
            maxOf(viewportBaseBottomPadding, navigationInsetBottom),
        )
        (viewport as? ViewGroup)?.clipToPadding = true
        viewport.layoutParams = (viewport.layoutParams ?: LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun updateStatusBar() {
        val window = (context as? Activity)?.window ?: return
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (hideStatusBarOnOpen && open) {
                window.insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                window.insetsController?.show(WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (hideStatusBarOnOpen && open) View.SYSTEM_UI_FLAG_FULLSCREEN else 0
        }
    }

    private companion object {
        const val TYPE_FRONT = 1
        const val TYPE_BACK = 2
        const val TYPE_SLIDE = 3
        const val TYPE_PERMANENT = 4
        const val POSITION_AUTOMATIC = 1
        const val POSITION_RIGHT = 3
        const val KEYBOARD_ON_DRAG = 1
    }
}
