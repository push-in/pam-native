package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import kotlin.math.abs

internal fun drawerIsVisuallyOpen(requestedOpen: Boolean, permanent: Boolean): Boolean =
    requestedOpen || permanent

/** A slide drawer must leave the viewport completely when its requested state is closed. */
internal fun slideDrawerRestingX(visuallyOpen: Boolean, openX: Float, closedX: Float): Float =
    if (visuallyOpen) openX else closedX

internal fun drawerContentVisible(visuallyOpen: Boolean, permanent: Boolean, tracking: Boolean = false): Boolean =
    visuallyOpen || permanent || tracking

internal fun permanentDrawerContentClip(
    viewportLeft: Int,
    viewportRight: Int,
    drawerWidth: Int,
    right: Boolean,
): Pair<Int, Int> {
    val safeLeft = viewportLeft.coerceAtLeast(0)
    val safeRight = viewportRight.coerceAtLeast(safeLeft)
    val reservation = drawerWidth.coerceIn(0, safeRight - safeLeft)
    return if (right) {
        safeLeft + reservation to safeRight
    } else {
        safeLeft to safeRight - reservation
    }
}

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
    private var progressAnimator: ValueAnimator? = null
    private var onOpen: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var navigationInsetBottom = 0
    private var statusInsetTop = 0
    private var insetDrawer: View? = null
    private var drawerBaseBottomPadding = 0
    private var insetViewport: View? = null
    private var viewportBaseBottomPadding = 0
    private var statusBarAppearanceBeforeOpen: Int? = null
    private var systemUiVisibilityBeforeOpen: Int? = null
    private val permanentContentClipBounds = Rect()

    init {
        // The drawer animates inside this viewport. Clipping prevents a closed
        // child from leaking into neighbouring layouts when the host is used
        // in a bounded pane instead of as a full-screen root.
        clipChildren = true
        clipToPadding = true
        setWillNotDraw(false)
        setOnApplyWindowInsetsListener { _, insets ->
            val nextTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val nextBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (statusInsetTop != nextTop || navigationInsetBottom != nextBottom) {
                statusInsetTop = nextTop
                navigationInsetBottom = nextBottom
                if (childCount > 1) {
                    enforceDrawerViewport(getChildAt(1))
                }
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        post {
            val insets = rootWindowInsets ?: return@post
            val nextTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val nextBottom = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (statusInsetTop != nextTop || navigationInsetBottom != nextBottom) {
                statusInsetTop = nextTop
                navigationInsetBottom = nextBottom
                if (childCount > 1) {
                    enforceDrawerViewport(getChildAt(1))
                }
            }
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
        val wasVisuallyOpen = drawerIsVisuallyOpen(open, permanent)
        if (open == value) {
            updateDrawer(false)
            return
        }
        open = value
        val isVisuallyOpen = drawerIsVisuallyOpen(open, permanent)
        updateStatusBar()
        // Requested state can change while a permanent drawer remains
        // visually open. Animating that no-op retains a stale permanent
        // content translation after DRAWER_TYPE changes back to Front.
        updateDrawer(animated && wasVisuallyOpen != isVisuallyOpen)
        if (wasVisuallyOpen != isVisuallyOpen) {
            if (isVisuallyOpen) onOpen?.invoke() else onClose?.invoke()
        }
    }

    fun setDrawerType(value: Int) {
        drawerType = value.coerceIn(TYPE_FRONT, TYPE_PERMANENT)
        updateDrawer(false)
        if (childCount > 1) {
            enforceDrawerViewport(getChildAt(1))
        }
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
        updatePermanentContentClip(content)
        val drawingTime = drawingTime
        if (resolvedType() == TYPE_BACK) {
            if (drawer.visibility == View.VISIBLE) drawChild(canvas, drawer, drawingTime)
            drawChild(canvas, content, drawingTime)
        } else {
            drawChild(canvas, content, drawingTime)
            if (
                drawer.visibility == View.VISIBLE &&
                progress > 0f &&
                resolvedType() != TYPE_PERMANENT
            ) {
                overlayPaint.color = overlayColor
                overlayPaint.alpha = ((overlayColor ushr 24) * progress).toInt()
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            }
            if (drawer.visibility == View.VISIBLE) drawChild(canvas, drawer, drawingTime)
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
                if (tracking) getChildAt(1)?.visibility = View.VISIBLE
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
        content.animate().cancel()
        drawer.animate().cancel()
        progressAnimator?.cancel()
        progressAnimator = null
        val type = resolvedType()
        val visuallyOpen = drawerIsVisuallyOpen(open, type == TYPE_PERMANENT)
        val width = drawerWidthPx()
        val direction = if (isRight()) -1f else 1f
        val openX = if (isRight()) this.width.toFloat() - width else 0f
        val closedX = if (isRight()) this.width.toFloat() else -width
        val drawerTarget = when (type) {
            TYPE_PERMANENT -> 0f
            TYPE_BACK -> openX
            TYPE_SLIDE -> slideDrawerRestingX(visuallyOpen, openX, closedX)
            else -> if (visuallyOpen) openX else closedX
        }
        val contentTarget = when (type) {
            TYPE_BACK, TYPE_SLIDE -> if (visuallyOpen) direction * width else 0f
            // The engine gives permanent drawer children disjoint frames, so
            // translating either child here would reserve the drawer twice.
            TYPE_PERMANENT -> 0f
            else -> 0f
        }
        val targetProgress = if (visuallyOpen && type != TYPE_PERMANENT) 1f else 0f
        if (drawerContentVisible(visuallyOpen, type == TYPE_PERMANENT)) {
            drawer.visibility = View.VISIBLE
        }
        updatePermanentContentClip(content)
        if (!animated || PamMotionPolicy.isReduced(context)) {
            drawer.translationX = drawerTarget
            content.translationX = contentTarget
            progress = targetProgress
            drawer.visibility = if (drawerContentVisible(visuallyOpen, type == TYPE_PERMANENT)) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            invalidate()
            return
        }
        drawer.animate().translationX(drawerTarget).setDuration(200).withEndAction {
            if (!drawerContentVisible(open, resolvedType() == TYPE_PERMANENT)) {
                drawer.visibility = View.INVISIBLE
            }
        }.start()
        content.animate().translationX(contentTarget).setDuration(200).start()
        progressAnimator = ValueAnimator.ofFloat(progress, targetProgress).apply {
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
            TYPE_SLIDE -> closedX
            else -> closedX
        }
        progress = value.coerceIn(0f, 1f)
        drawer.visibility = View.VISIBLE
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

    private fun updatePermanentContentClip(content: View) {
        if (resolvedType() != TYPE_PERMANENT || content.width <= 0 || content.height <= 0) {
            if (content.clipBounds != null) content.clipBounds = null
            return
        }
        val reservation = drawerWidthPx().toInt()
        val partitionedContentWidth = (width - reservation).coerceAtLeast(0)
        if (content.width <= partitionedContentWidth + 1) {
            if (content.clipBounds != null) content.clipBounds = null
            return
        }
        val safeViewport = (content as? ViewGroup)?.getChildAt(0)
        val viewportLeft = safeViewport?.left ?: 0
        val viewportRight = safeViewport?.right ?: content.width
        val (left, right) = permanentDrawerContentClip(
            viewportLeft = viewportLeft,
            viewportRight = viewportRight,
            drawerWidth = reservation,
            right = isRight(),
        )
        permanentContentClipBounds.set(left, 0, right, content.height)
        if (content.clipBounds != permanentContentClipBounds) {
            content.clipBounds = permanentContentClipBounds
        }
    }

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
        val drawerWidth = drawerWidthPx().toInt()
        val drawerTop = if (resolvedType() == TYPE_PERMANENT) statusInsetTop else 0
        val drawerParams = drawer.layoutParams as? LayoutParams
        if (drawerParams == null) {
            drawer.postOnAnimation {
                drawer.layoutParams = LayoutParams(
                    drawerWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply { topMargin = drawerTop }
            }
        } else if (
            drawerParams.width != drawerWidth
            || drawerParams.height != ViewGroup.LayoutParams.MATCH_PARENT
            || drawerParams.topMargin != drawerTop
        ) {
            drawerParams.width = drawerWidth
            drawerParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            drawerParams.topMargin = drawerTop
            drawer.postOnAnimation {
                if (drawer.isAttachedToWindow) drawer.requestLayout()
            }
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
        val viewportParams = viewport.layoutParams
        if (viewportParams == null) {
            viewport.postOnAnimation {
                viewport.layoutParams = LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        } else if (
            viewportParams.width != ViewGroup.LayoutParams.MATCH_PARENT
            || viewportParams.height != ViewGroup.LayoutParams.MATCH_PARENT
        ) {
            viewportParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            viewportParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            viewport.postOnAnimation {
                if (viewport.isAttachedToWindow) viewport.requestLayout()
            }
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
            val controller = window.insetsController ?: return
            if (open) {
                if (statusBarAppearanceBeforeOpen == null) {
                    statusBarAppearanceBeforeOpen = controller.systemBarsAppearance
                }
                val lightStatusBar = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                controller.setSystemBarsAppearance(
                    if (drawerBackgroundIsLight()) lightStatusBar else 0,
                    lightStatusBar,
                )
            } else {
                statusBarAppearanceBeforeOpen?.let { appearance ->
                    val lightStatusBar = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    controller.setSystemBarsAppearance(appearance, lightStatusBar)
                }
                statusBarAppearanceBeforeOpen = null
            }
        } else {
            @Suppress("DEPRECATION")
            if (open) {
                if (systemUiVisibilityBeforeOpen == null) {
                    systemUiVisibilityBeforeOpen = window.decorView.systemUiVisibility
                }
                window.decorView.systemUiVisibility = when {
                    hideStatusBarOnOpen -> View.SYSTEM_UI_FLAG_FULLSCREEN
                    drawerBackgroundIsLight() ->
                        window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    else ->
                        window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            } else {
                systemUiVisibilityBeforeOpen?.let { appearance ->
                    window.decorView.systemUiVisibility = appearance
                }
                systemUiVisibilityBeforeOpen = null
            }
        }
    }

    private fun drawerBackgroundIsLight(): Boolean {
        val drawer = getChildAt(1) ?: return false
        val color = (drawer.background as? ColorDrawable)?.color
            ?: drawer.backgroundTintList?.defaultColor
            ?: return false
        val luminance = (
            0.2126 * Color.red(color) +
                0.7152 * Color.green(color) +
                0.0722 * Color.blue(color)
            ) / 255.0
        return luminance > 0.5
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
