package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Outline
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.SearchView
import android.widget.Toolbar
import android.graphics.Color
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import java.util.IdentityHashMap

internal class PamNavigationHost(context: Context) : FrameLayout(context) {
    var operation: Int = OPERATION_IDLE
    var transition: Int = TRANSITION_PLATFORM_DEFAULT
    var durationMs: Long = 240L
    var navigationOrientation: Int = 1
        set(value) {
            field = value.coerceIn(1, 8)
            applyOrientation()
        }
    var screenTitle: String = ""; set(value) { field = value; applyControllerChrome() }
    var headerShown: Boolean = false; set(value) { field = value; applyControllerChrome() }
    var headerTransparent: Boolean = false; set(value) { field = value; applyControllerChrome() }
    var headerBackgroundColor: Int? = null; set(value) { field = value; applyControllerChrome() }
    var headerTintColor: Int? = null; set(value) { field = value; applyControllerChrome() }
    var headerShadowVisible: Boolean = true; set(value) { field = value; applyControllerChrome() }
    var headerSearchEnabled: Boolean = false; set(value) { field = value; applyControllerChrome() }
    var headerSearchPlaceholder: String = "Search"; set(value) { field = value; applyControllerChrome() }
    var onSearchChange: ((String) -> Unit)? = null
    var screenPresentation: Int = PRESENTATION_CARD
    var sheetDetents: List<Float> = listOf(1f)
    var sheetInitialDetentIndex: Int = 1
    var sheetCornerRadius: Float = 0f
    var onActiveRouteChanged: (() -> Unit)? = null
    private var revision: Long = 0L
    private var activeRoute: View? = null
    private var movingRoute: View? = null
    private var layeredRoute: View? = null
    private val awaitingFirstLayout: MutableSet<View> = java.util.Collections.newSetFromMap(IdentityHashMap())
    private var layoutGeneration = 0L
    private var running: ValueAnimator? = null
    private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var pendingObserver: ViewTreeObserver? = null
    private var gestureEnabled = true
    private var gestureEdgeWidth = 24f
    private var gestureThreshold = 0.35f
    private var onGesturePop: (() -> Unit)? = null
    private var onTransitionEnd: (() -> Unit)? = null
    private var onGestureStart: (() -> Unit)? = null
    private var onGestureEnd: (() -> Unit)? = null
    private var onGestureCancel: (() -> Unit)? = null
    /** A touch that may become a back gesture (it went down where one can start). */
    private var gestureTracking = false
    /** The back gesture began: its two routes below are captured and moving. */
    private var gesturePrepared = false
    private var gestureIncoming: View? = null
    private var gestureOutgoing: View? = null
    private var gestureSettle: ValueAnimator? = null
    /**
     * Routes whose alpha/translation/scale/rotation this host changed for a
     * transition or a gesture. Every exit path (end, cancel, interruption,
     * detach, background) restores them, so no route keeps a dimmed or moved
     * frame after its animation stopped.
     */
    private val transformedRoutes: MutableSet<View> = java.util.Collections.newSetFromMap(IdentityHashMap())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureDirection = 1
    private var fullScreenGestureEnabled = false
    private var velocityTracker: VelocityTracker? = null
    private var predictiveBackActive = false
    private var predictiveBackCommitted = false
    private var predictiveBackProgress = 0f
    /**
     * A released predictive back waits for the runtime's verdict: Back may
     * be consumed by the app (closing a panel, a selection, a search) and pop
     * nothing. The gesture keeps its frame until the next committed frame
     * says whether a pop followed; without one it settles back.
     */
    private var predictiveBackAwaiting = false
    private var predictiveBackNavigated = false
    private val predictiveBackTimeout = Runnable { resolvePredictiveBack() }
    private val sharedElements = PamSharedElements(this)
    private val routeControllers = IdentityHashMap<View, PamRouteFragment>()
    private var suppressControllerRemoval = false
    private var nextControllerId = 1L
    private val nativeToolbar = Toolbar(context).apply {
        visibility = View.GONE
        minimumHeight = dp(56f).toInt()
        setTitleTextAppearance(context, android.R.style.TextAppearance_Material_Widget_ActionBar_Title)
    }
    private var nativeSearch: SearchView? = null

    init {
        id = View.generateViewId()
        clipChildren = true
        clipToPadding = true
    }

    /**
     * Child order is the engine's route order (the navigator renders its top
     * route last, before an outgoing route only while popping), so the
     * destination of a transition is always read from position, never from
     * which route happened to be inserted last: a reset and a push folded
     * into one commit create their routes in node-id order, not stack order.
     */
    fun insert(view: View, index: Int) {
        val isInitialRoute = childCount == 0
        val isVisibleRoute = isInitialRoute || view === activeRoute
        val moved = movingRoute === view
        if (moved) movingRoute = null
        setRouteVisibility(view, if (isVisibleRoute) View.VISIBLE else View.INVISIBLE)
        // A screen mounted hidden (prewarmed, kept alive, or pushed and not
        // yet animating) is measured and laid out on the next frame, so its
        // mount and its first layout never add up in a single frame.
        // Before the host's own first layout (a cold start routed straight to
        // a deep link: stack [home, list] in one commit) nothing is on screen
        // yet, so the top route is laid out and shown in the very first frame.
        if (!isVisibleRoute && !moved && isAttachedToWindow && isLaidOut) {
            awaitingFirstLayout.add(view)
            releaseFirstLayoutOnNextFrame(view)
        }
        view.importantForAccessibility = if (isVisibleRoute) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        val position = index.coerceIn(0, childCount)
        // Attached hosts mount the route straight through its controller: the
        // view enters the window once instead of being added, removed for the
        // fragment and moved back (three attaches and two detaches of the
        // whole screen subtree).
        if (!isAttachedToWindow || !addRouteController(view, params, position)) {
            addView(view, position, params)
            if (isAttachedToWindow) ensureRouteController(view)
        }
        if (isInitialRoute) setActiveRoute(view)
    }

    /**
     * Releases a hidden route's first layout on the frame after the one that
     * lays out its mount. A mount from a frame callback (engine batches) is
     * laid out by that frame's traversal, so the next frame callback releases
     * it; a mount outside a frame has not been laid out yet when the next
     * callback runs (callbacks precede the traversal), so it waits one more.
     */
    private fun releaseFirstLayoutOnNextFrame(view: View) {
        val mountLayout = layoutGeneration
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            private var deferred = false

            override fun doFrame(frameTimeNanos: Long) {
                if (!deferred && layoutGeneration == mountLayout && view in awaitingFirstLayout) {
                    deferred = true
                    Choreographer.getInstance().postFrameCallback(this)
                    return
                }
                releaseFirstLayout(view)
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        childrenSnapshot().forEach(::ensureRouteController)
        updateControllerLifecycles()
        overlay.add(nativeToolbar)
        applyControllerChrome()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layoutGeneration++
        if (awaitingFirstLayout.isEmpty()) {
            super.onLayout(changed, left, top, right, bottom)
        } else {
            // Routes are full-size frames; skip the ones awaiting their own
            // frame (their engine-positioned descendants would lay out and
            // bind their lists now otherwise).
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == View.GONE || child in awaitingFirstLayout) continue
                child.layout(
                    paddingLeft,
                    paddingTop,
                    paddingLeft + child.measuredWidth,
                    paddingTop + child.measuredHeight,
                )
            }
        }
        nativeToolbar.layout(0, 0, width, dp(56f).toInt())
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        if (!suppressControllerRemoval) awaitingFirstLayout.remove(child)
        if (suppressControllerRemoval) return
        // A route leaves at rest: mounted again later (kept alive, parked) it
        // must not come back faded or moved.
        if (transformedRoutes.remove(child)) reset(child)
        if (child === activeRoute) {
            activeRoute = null
            if (childCount > 0) {
                getChildAt(childCount - 1).also { replacement ->
                    setRouteVisibility(replacement, View.VISIBLE)
                    replacement.importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    setActiveRoute(replacement)
                    applyRoutePresentation(replacement)
                }
            }
            restoreIdleRoutes()
        }
        val fragment = routeControllers.remove(child) ?: return
        fragmentManager()?.takeUnless(FragmentManager::isStateSaved)?.let { manager ->
            manager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
    }

    /**
     * Fired (at most once per frame) after a route's visibility changed:
     * a route hidden below a full-screen route is covered, the top route and
     * the routes of a running transition are visible.
     */
    var onRouteVisibilityChanged: (() -> Unit)? = null
    private var routeVisibilityNotifyPosted = false
    private val notifyRouteVisibility = Runnable {
        routeVisibilityNotifyPosted = false
        onRouteVisibilityChanged?.invoke()
    }

    private fun setRouteVisibility(route: View, value: Int) {
        if (route.visibility == value) return
        route.visibility = value
        if (!routeVisibilityNotifyPosted && onRouteVisibilityChanged != null) {
            routeVisibilityNotifyPosted = true
            post(notifyRouteVisibility)
        }
    }

    fun isActiveRoute(view: View): Boolean = view === activeRoute

    internal fun routeControllerCount(): Int = routeControllers.size

    internal fun activeControllerLifecycle(): Lifecycle.State? =
        routeControllers[activeRoute]?.lifecycle?.currentState

    fun removeRoute(view: View) {
        if (view.parent === this) removeView(view)
        else routeControllers.remove(view)?.let { fragment ->
            fragmentManager()?.beginTransaction()?.remove(fragment)?.commitNowAllowingStateLoss()
        }
    }

    /**
     * Moves a route to [index] without detaching it from the window (no
     * cancelled touches, no re-attach of its views). A route still waiting
     * for its deferred first layout is laid out now.
     */
    fun reorderRoute(view: View, index: Int) {
        if (view.parent !== this) return
        if (indexOfChild(view) != index) {
            val params = view.layoutParams
            detachViewFromParent(view)
            attachViewToParent(view, index.coerceIn(0, childCount), params)
            requestLayout()
            invalidate()
        }
        releaseFirstLayout(view)
    }

    private fun releaseFirstLayout(view: View) {
        if (!awaitingFirstLayout.remove(view)) return
        view.requestLayout()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // FrameLayout re-measures MATCH_PARENT children at full size when its
        // own spec is not exact; keep routes awaiting their frame empty.
        for (child in awaitingFirstLayout) {
            if (child.parent === this && child.measuredHeight != 0) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                )
            }
        }
    }

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        if (child in awaitingFirstLayout) {
            // Laid out (empty) at zero size until its own frame.
            child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            return
        }
        super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed)
    }

    fun detachRouteForMove(view: View) {
        if (view.parent !== this) return
        movingRoute = view
        suppressControllerRemoval = true
        try {
            removeView(view)
        } finally {
            suppressControllerRemoval = false
        }
    }

    fun navigate(nextRevision: Long) {
        if (nextRevision == revision) return
        revision = nextRevision
        if (predictiveBackAwaiting) predictiveBackNavigated = true
        scheduleTransition()
    }

    fun setGestureNavigation(
        enabled: Boolean,
        edgeWidth: Float,
        threshold: Float,
        direction: Int = 1,
        fullScreen: Boolean = false,
        onPop: (() -> Unit)?,
        onTransitionEnd: (() -> Unit)?,
        onGestureStart: (() -> Unit)?,
        onGestureEnd: (() -> Unit)?,
        onGestureCancel: (() -> Unit)?,
    ) {
        gestureEnabled = enabled
        gestureEdgeWidth = edgeWidth.coerceIn(8f, 96f)
        gestureThreshold = threshold.coerceIn(0.1f, 0.9f)
        gestureDirection = direction.coerceIn(1, 2)
        fullScreenGestureEnabled = fullScreen
        onGesturePop = onPop
        this.onTransitionEnd = onTransitionEnd
        this.onGestureStart = onGestureStart
        this.onGestureEnd = onGestureEnd
        this.onGestureCancel = onGestureCancel
    }

    fun startPredictiveBack(): Boolean {
        endGestureSettle()
        if (!gestureEnabled || childCount < 2 || running != null || gesturePrepared) return false
        predictiveBackActive = true
        predictiveBackCommitted = false
        predictiveBackProgress = 0f
        prepareGesture()
        applyGestureProgress(0f, layoutDirection == View.LAYOUT_DIRECTION_RTL)
        return true
    }

    fun updatePredictiveBack(progress: Float) {
        if (!predictiveBackActive) return
        predictiveBackProgress = progress.coerceIn(0f, 1f)
        applyGestureProgress(
            predictiveBackProgress,
            layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    fun cancelPredictiveBack() {
        if (!predictiveBackActive) return
        predictiveBackActive = false
        settleGesture(
            predictiveBackProgress,
            complete = false,
            layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
        predictiveBackProgress = 0f
    }

    /**
     * The user released a predictive back. The routes stay where the gesture
     * left them until the runtime answers: a pop completes it, while a Back
     * the app consumed (a frame with no navigation, see resolvePredictiveBack)
     * settles them back like a cancelled gesture. A key Back starts at
     * progress 0, so a consumed one changes nothing on screen.
     */
    fun commitPredictiveBack() {
        if (!predictiveBackActive) return
        predictiveBackActive = false
        predictiveBackAwaiting = true
        predictiveBackNavigated = false
        removeCallbacks(predictiveBackTimeout)
        postDelayed(predictiveBackTimeout, PREDICTIVE_BACK_VERDICT_TIMEOUT_MS)
    }

    /**
     * Called once a runtime frame is committed. A released predictive back
     * that no navigation followed was consumed by the app: settle back.
     */
    fun resolvePredictiveBack() {
        if (!predictiveBackAwaiting || predictiveBackNavigated) return
        predictiveBackAwaiting = false
        removeCallbacks(predictiveBackTimeout)
        settleGesture(
            predictiveBackProgress,
            complete = false,
            rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    private fun takePredictiveBackVerdict() {
        if (!predictiveBackAwaiting) return
        predictiveBackAwaiting = false
        predictiveBackNavigated = false
        removeCallbacks(predictiveBackTimeout)
        if (operation != OPERATION_POP) return
        if (predictiveBackProgress <= 0f) {
            // A key Back (no drag): the pop plays its own transition, like
            // a Back on a stack with no predictive gesture.
            dropPreparedGesture()
            return
        }
        predictiveBackCommitted = true
        predictiveBackProgress = 1f
        onGestureEnd?.invoke()
    }

    /** Puts the routes of a prepared, never dragged gesture back at rest. */
    private fun dropPreparedGesture() {
        val incoming = gestureIncoming
        val outgoing = gestureOutgoing
        gesturePrepared = false
        gestureIncoming = null
        gestureOutgoing = null
        incoming?.let(::reset)
        outgoing?.let(::reset)
        clearSharedElements()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureTracking = false
            if (!gestureEnabled || childCount < 2 || running != null || gestureSettle != null ||
                predictiveBackActive || gesturePrepared
            ) {
                return false
            }
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val edge = dp(gestureEdgeWidth)
            val withinEdge = fullScreenGestureEnabled || gestureDirection == 2 ||
                if (rtl) event.x >= width - edge else event.x <= edge
            if (!withinEdge) return false
            // Only remember where the touch went down: nothing changes on
            // screen (or in the route lifecycles) until it moves like a back
            // gesture. A tap, a scroll or a cancelled touch leaves no trace.
            gestureStartX = event.x
            gestureStartY = event.y
            gestureTracking = true
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
            return false
        }
        if (!gestureTracking) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (gestureBegins(event)) {
                    prepareGesture()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
        return false
    }

    /**
     * True once the touch travelled past the touch slop along the gesture
     * axis, more than across it; a touch that first moves across it (a list
     * scroll) stops being a candidate.
     */
    private fun gestureBegins(event: MotionEvent): Boolean {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val dx = event.x - gestureStartX
        val dy = event.y - gestureStartY
        val along = if (gestureDirection == 2) dy else if (rtl) -dx else dx
        val across = kotlin.math.abs(if (gestureDirection == 2) dx else dy)
        val slop = maxOf(touchSlop.toFloat(), dp(6f))
        if (across > slop && across > along) {
            stopTracking()
            return false
        }
        return along > slop && along > across
    }

    private fun stopTracking() {
        gestureTracking = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gestureTracking) return false
        velocityTracker?.addMovement(event)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val distance = (if (gestureDirection == 2) event.y - gestureStartY
            else if (rtl) gestureStartX - event.x else event.x - gestureStartX)
            .coerceAtLeast(0f)
        val extent = if (gestureDirection == 2) height else width
        val progress = (distance / extent.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // No child took the touch: the gesture begins here instead
                // of in onInterceptTouchEvent.
                if (!gesturePrepared) {
                    if (!gestureBegins(event)) return gestureTracking
                    prepareGesture()
                }
                applyGestureProgress(progress, rtl)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.computeCurrentVelocity(1_000)
                val rawVelocity = if (gestureDirection == 2) velocityTracker?.yVelocity ?: 0f
                    else velocityTracker?.xVelocity ?: 0f
                val velocity = if (gestureDirection == 2 || !rtl) rawVelocity else -rawVelocity
                val prepared = gesturePrepared
                stopTracking()
                if (prepared) {
                    val complete = event.actionMasked == MotionEvent.ACTION_UP &&
                        (progress >= gestureThreshold || velocity >= dp(700f))
                    settleGesture(progress, complete, rtl)
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun prepareGesture() {
        val incoming = getChildAt(childCount - 2)
        val outgoing = getChildAt(childCount - 1)
        gesturePrepared = true
        gestureIncoming = incoming
        gestureOutgoing = outgoing
        transformedRoutes += incoming
        transformedRoutes += outgoing
        setActiveRoute(incoming)
        applyRoutePresentation(incoming)
        setRouteVisibility(incoming, View.VISIBLE)
        setRouteVisibility(outgoing, View.VISIBLE)
        prepareSharedElements(incoming, outgoing)
        onGestureStart?.invoke()
    }

    private fun applyGestureProgress(progress: Float, rtl: Boolean) {
        val incoming = gestureIncoming ?: return
        val outgoing = gestureOutgoing ?: return
        val sign = if (rtl) -1f else 1f
        if (gestureDirection == 2) {
            outgoing.translationY = height * progress
            incoming.translationY = -height * 0.12f * (1f - progress)
        } else {
            outgoing.translationX = sign * width * progress
            incoming.translationX = -sign * width * 0.28f * (1f - progress)
        }
        incoming.alpha = 0.82f + 0.18f * progress
        applySharedElementProgress(progress)
    }

    private fun settleGesture(start: Float, complete: Boolean, rtl: Boolean) {
        val incoming = gestureIncoming
        val outgoing = gestureOutgoing
        if (incoming == null || outgoing == null) {
            abortGesture()
            return
        }
        gestureSettle?.cancel()
        gestureSettle = ValueAnimator.ofFloat(start, if (complete) 1f else 0f).apply {
            duration = ((if (complete) 1f - start else start) * 220)
                .toLong()
                .coerceAtLeast(80)
            interpolator = DecelerateInterpolator(1.75f)
            addUpdateListener { applyGestureProgress(it.animatedValue as Float, rtl) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gestureSettle === animation) gestureSettle = null
                    endGesture(incoming, outgoing, complete)
                }
            })
            start()
        }
    }

    /**
     * Ends a settled back gesture on the routes it moved, whatever happened
     * to the stack meanwhile (a route removed or inserted while it settled).
     */
    private fun endGesture(incoming: View, outgoing: View, complete: Boolean) {
        gesturePrepared = false
        gestureIncoming = null
        gestureOutgoing = null
        reset(incoming)
        reset(outgoing)
        clearSharedElements()
        val incomingMounted = incoming.parent === this
        val outgoingMounted = outgoing.parent === this
        if (complete) {
            predictiveBackCommitted = true
            if (outgoingMounted) setRouteVisibility(outgoing, View.INVISIBLE)
            if (incomingMounted) {
                setRouteVisibility(incoming, View.VISIBLE)
                setActiveRoute(incoming)
            }
            onGestureEnd?.invoke()
            onGesturePop?.invoke()
        } else {
            if (incomingMounted && outgoingMounted) setRouteVisibility(incoming, View.INVISIBLE)
            if (outgoingMounted) {
                setRouteVisibility(outgoing, View.VISIBLE)
                setActiveRoute(outgoing)
            } else {
                showOnlyTop()
            }
            onGestureCancel?.invoke()
        }
        restoreIdleRoutes()
    }

    /** Jumps a settling back gesture to its end (its routes restored). */
    private fun endGestureSettle() {
        gestureSettle?.end()
    }

    /**
     * Drops a back gesture that a navigation, a detach or the app going to
     * the background interrupted before it settled: its routes go back to
     * rest and the routes the interrupting change shows decide what is
     * visible.
     */
    private fun abortGesture() {
        stopTracking()
        endGestureSettle()
        if (predictiveBackAwaiting) {
            predictiveBackAwaiting = false
            predictiveBackNavigated = false
            removeCallbacks(predictiveBackTimeout)
        }
        val wasPrepared = gesturePrepared || predictiveBackActive
        val incoming = gestureIncoming
        val outgoing = gestureOutgoing
        gesturePrepared = false
        gestureIncoming = null
        gestureOutgoing = null
        predictiveBackActive = false
        if (!wasPrepared) return
        incoming?.let(::reset)
        outgoing?.let(::reset)
        clearSharedElements()
        // A committed predictive back is completed by the pop that follows.
        if (predictiveBackCommitted) return
        if (outgoing != null && outgoing.parent === this) setActiveRoute(outgoing)
        onGestureCancel?.invoke()
    }

    /**
     * Restores every route this host moved or faded once nothing animates
     * any more (no transition, no gesture), and hides the routes a finished
     * gesture left visible under the top one.
     */
    private fun restoreIdleRoutes() {
        if (running != null || gestureSettle != null || gesturePrepared || predictiveBackActive) return
        if (transformedRoutes.isEmpty()) return
        val routes = transformedRoutes.toList()
        transformedRoutes.clear()
        routes.forEach(::reset)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        overlay.remove(nativeToolbar)
        abortGesture()
        clearSharedElements()
        clearPendingTransition()
        running?.cancel()
        running = null
        restoreIdleRoutes()
        super.onDetachedFromWindow()
    }

    /**
     * The app went to the background (or the window was hidden) during a
     * transition or a back gesture: finish it now instead of leaving a route
     * faded or moved if its frames stop while the window is not drawn.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) return
        abortGesture()
        running?.end()
        restoreIdleRoutes()
    }

    private fun scheduleTransition() {
        clearPendingTransition()
        val observer = viewTreeObserver
        if (!isAttachedToWindow || !observer.isAlive) {
            post { runTransition() }
            return
        }
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        listener = ViewTreeObserver.OnPreDrawListener {
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            if (pendingPreDraw === listener) {
                pendingPreDraw = null
                pendingObserver = null
            }
            runTransition()
            true
        }
        pendingPreDraw = listener
        pendingObserver = observer
        observer.addOnPreDrawListener(listener)
        invalidate()
    }

    private fun clearPendingTransition() {
        val listener = pendingPreDraw ?: return
        pendingObserver
            ?.takeIf { it.isAlive }
            ?.removeOnPreDrawListener(listener)
        pendingPreDraw = null
        pendingObserver = null
    }

    private fun runTransition() {
        // A navigation interrupts whatever still moves the routes: the
        // previous transition ends on its destination and a back gesture in
        // progress (or settling) is dropped, so no route keeps its frame.
        running?.cancel()
        takePredictiveBackVerdict()
        abortGesture()
        if (childCount == 0) {
            restoreIdleRoutes()
            return
        }
        val top = getChildAt(childCount - 1)
        if (operation != OPERATION_POP && top in awaitingFirstLayout) {
            // Mounted in this frame: lay it out on the next one, then show it
            // (mount and first layout never share a frame).
            releaseFirstLayout(top)
            scheduleTransition()
            return
        }

        val outgoing: View?
        val incoming: View
        when (operation) {
            OPERATION_PUSH, OPERATION_REPLACE -> {
                incoming = getChildAt(childCount - 1)
                outgoing = activeRoute
                    ?.takeIf { it !== incoming && it.parent === this }
                    ?: childrenSnapshot().lastOrNull { it !== incoming }
            }
            OPERATION_POP -> {
                incoming = getChildAt((childCount - 2).coerceAtLeast(0))
                outgoing = if (childCount > 1) getChildAt(childCount - 1) else null
            }
            else -> {
                showOnlyTop()
                restoreIdleRoutes()
                return
            }
        }
        if (incoming in awaitingFirstLayout) {
            releaseFirstLayout(incoming)
            scheduleTransition()
            return
        }
        setActiveRoute(incoming)
        applyRoutePresentation(incoming)

        if (predictiveBackCommitted && operation == OPERATION_POP) {
            predictiveBackCommitted = false
            predictiveBackProgress = 0f
            finish(incoming, outgoing)
            return
        }

        for (index in 0 until childCount) {
            getChildAt(index).apply {
                setRouteVisibility(this, if (this === incoming || this === outgoing) View.VISIBLE else View.INVISIBLE)
                importantForAccessibility = if (this === incoming) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }

        prepareSharedElements(incoming, outgoing)

        val actualTransition =
            if (transition == TRANSITION_PLATFORM_DEFAULT) TRANSITION_SLIDE_FROM_RIGHT else transition
        val sharedDuration = sharedElements.durationMs
        val actualDuration = if (durationMs <= 0L || animationsDisabled()) {
            0L
        } else {
            maxOf(durationMs, sharedDuration).coerceAtMost(2_000L)
        }
        transformedRoutes += incoming
        outgoing?.let { transformedRoutes += it }
        prepare(incoming, outgoing, actualTransition)
        if (actualDuration == 0L || actualTransition == TRANSITION_NONE) {
            finish(incoming, outgoing)
            return
        }

        // The outgoing screen only fades/slides: draw it once into a layer and
        // composite it, instead of re-rendering it offscreen for its alpha
        // on every frame of the transition.
        outgoing?.takeIf { it.layerType == View.LAYER_TYPE_NONE }?.let {
            it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layeredRoute = it
        }
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = actualDuration
            interpolator = DecelerateInterpolator(1.75f)
            addUpdateListener { applyProgress(incoming, outgoing, actualTransition, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running === animation) running = null
                    finish(incoming, outgoing)
                }
            })
            start()
        }
    }

    private fun prepare(incoming: View, outgoing: View?, kind: Int) {
        applyProgress(incoming, outgoing, kind, 0f)
    }

    private fun applyProgress(incoming: View, outgoing: View?, kind: Int, progress: Float) {
        val width = width.coerceAtLeast(1).toFloat()
        val height = height.coerceAtLeast(1).toFloat()
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val semanticSign = if (rtl) -1f else 1f
        val popping = operation == OPERATION_POP
        val direction = when (kind) {
            TRANSITION_SLIDE_FROM_LEFT -> -1f
            else -> semanticSign
        }

        incoming.alpha = 1f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.translationX = 0f
        incoming.translationY = 0f
        outgoing?.alpha = 1f
        outgoing?.scaleX = 1f
        outgoing?.scaleY = 1f
        outgoing?.translationX = 0f
        outgoing?.translationY = 0f

        when (kind) {
            TRANSITION_SLIDE_FROM_RIGHT, TRANSITION_SLIDE_FROM_LEFT -> {
                if (popping) {
                    incoming.translationX = -direction * width * 0.28f * (1f - progress)
                    outgoing?.translationX = direction * width * progress
                } else {
                    incoming.translationX = direction * width * (1f - progress)
                    outgoing?.translationX = -direction * width * 0.28f * progress
                }
                outgoing?.alpha = 1f - (progress * 0.18f)
            }
            TRANSITION_SLIDE_FROM_BOTTOM -> {
                if (popping) outgoing?.translationY = height * progress
                else incoming.translationY = height * (1f - progress)
                outgoing?.alpha = 1f - (progress * 0.12f)
            }
            TRANSITION_SLIDE_FROM_TOP -> {
                if (popping) outgoing?.translationY = -height * progress
                else incoming.translationY = -height * (1f - progress)
                outgoing?.alpha = 1f - (progress * 0.12f)
            }
            TRANSITION_FADE -> if (sharedElements.isEmpty) {
                incoming.alpha = progress
                outgoing?.alpha = 1f - progress
            } else if (popping) {
                // With a shared element only the top route fades: the
                // screen below stays put while its media flies home.
                outgoing?.alpha = 1f - progress
            } else {
                incoming.alpha = progress
            }
            TRANSITION_FADE_FROM_BOTTOM -> {
                incoming.alpha = progress
                incoming.translationY = height * 0.08f * (1f - progress)
            }
            TRANSITION_SCALE -> {
                incoming.alpha = progress
                val scale = 0.94f + (0.06f * progress)
                incoming.scaleX = scale
                incoming.scaleY = scale
            }
            TRANSITION_SHARED_AXIS_X -> {
                val sign = if (popping) -semanticSign else semanticSign
                incoming.alpha = progress
                incoming.translationX = sign * width * 0.12f * (1f - progress)
                outgoing?.alpha = 1f - progress
                outgoing?.translationX = -sign * width * 0.08f * progress
            }
            TRANSITION_SHARED_AXIS_Y -> {
                val sign = if (popping) -1f else 1f
                incoming.alpha = progress
                incoming.translationY = sign * height * 0.08f * (1f - progress)
                outgoing?.alpha = 1f - progress
                outgoing?.translationY = -sign * height * 0.05f * progress
            }
            TRANSITION_FLIP -> {
                incoming.alpha = progress
                incoming.rotationY = -90f * (1f - progress)
                outgoing?.rotationY = 90f * progress
            }
            TRANSITION_SIMPLE_PUSH -> {
                if (popping) outgoing?.translationX = direction * width * progress
                else incoming.translationX = direction * width * (1f - progress)
            }
        }
        applySharedElementProgress(progress)
    }

    private fun prepareSharedElements(incoming: View, outgoing: View?) {
        sharedElements.clear()
        if (outgoing == null || animationsDisabled() || durationMs <= 0L) return
        sharedElements.prepare(incoming, outgoing, durationMs)
    }

    private fun applySharedElementProgress(progress: Float) {
        sharedElements.apply(progress)
    }

    private fun clearSharedElements() {
        sharedElements.clear()
    }

    internal fun activeSharedElementCount(): Int = sharedElements.count

    private fun finish(incoming: View, outgoing: View?) {
        layeredRoute?.setLayerType(View.LAYER_TYPE_NONE, null)
        layeredRoute = null
        sharedElements.finish()
        running = null
        setActiveRoute(incoming)
        reset(incoming)
        outgoing?.let {
            reset(it)
            setRouteVisibility(it, if (keepsPreviousRouteVisible()) View.VISIBLE else View.INVISIBLE)
        }
        setRouteVisibility(incoming, View.VISIBLE)
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        outgoing?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        restoreIdleRoutes()
        onTransitionEnd?.invoke()
    }

    private fun showOnlyTop() {
        val top = if (childCount == 0) null else getChildAt(childCount - 1)
        top?.let(::setActiveRoute)
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                val active = index == childCount - 1
                setRouteVisibility(this, if (active) View.VISIBLE else View.INVISIBLE)
                importantForAccessibility = if (active) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
        }
    }

    private fun setActiveRoute(view: View) {
        if (activeRoute === view) return
        activeRoute = view
        updateControllerLifecycles()
        onActiveRouteChanged?.invoke()
    }

    private fun ensureRouteController(view: View) {
        if (routeControllers.containsKey(view)) return
        val manager = fragmentManager() ?: return
        if (manager.isStateSaved) return
        val position = indexOfChild(view)
        val params = view.layoutParams
        suppressControllerRemoval = true
        if (view.parent === this) removeView(view)
        suppressControllerRemoval = false
        commitRouteController(manager, view)
        // The FragmentManager re-adds the view right after the previously
        // added route fragment's view, not where the engine placed it.
        if (position < 0) return
        if (view.parent !== this) addView(view, position.coerceIn(0, childCount), params)
        restoreRoutePosition(view, position)
    }

    /** Adds [view] (not yet a child) through a new route controller. */
    private fun addRouteController(view: View, params: LayoutParams, position: Int): Boolean {
        if (view.parent != null || routeControllers.containsKey(view)) return false
        val manager = fragmentManager() ?: return false
        if (manager.isStateSaved) return false
        view.layoutParams = params
        commitRouteController(manager, view)
        if (view.parent !== this) addView(view, position.coerceIn(0, childCount), params)
        restoreRoutePosition(view, position)
        return true
    }

    private fun commitRouteController(manager: FragmentManager, view: View) {
        val fragment = PamRouteFragment().also { it.bind(view) }
        routeControllers[view] = fragment
        manager.beginTransaction()
            .setReorderingAllowed(true)
            .add(id, fragment, "pam-route-${id}-${nextControllerId++}")
            .setMaxLifecycle(
                fragment,
                if (view === activeRoute) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
            )
            .commitNow()
    }

    /** Moves [view] to [position] without taking it out of the window. */
    private fun restoreRoutePosition(view: View, position: Int) {
        if (view.parent !== this) return
        val target = position.coerceIn(0, childCount - 1)
        if (indexOfChild(view) == target) return
        val params = view.layoutParams
        detachViewFromParent(view)
        attachViewToParent(view, target, params)
        requestLayout()
        invalidate()
    }

    private fun updateControllerLifecycles() {
        val manager = fragmentManager() ?: return
        if (manager.isStateSaved || routeControllers.isEmpty()) return
        val transaction = manager.beginTransaction().setReorderingAllowed(true)
        routeControllers.forEach { (view, fragment) ->
            if (fragment.isAdded) {
                transaction.setMaxLifecycle(
                    fragment,
                    if (view === activeRoute) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
                )
            }
        }
        transaction.commitNowAllowingStateLoss()
    }

    private fun fragmentManager(): FragmentManager? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current.supportFragmentManager
            current = current.baseContext
        }
        return (current as? FragmentActivity)?.supportFragmentManager
    }

    private fun childrenSnapshot(): List<View> =
        (0 until childCount).map(::getChildAt)

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotationY = 0f
    }

    private fun animationsDisabled(): Boolean = PamMotionPolicy.isReduced(context)

    /** Orientation the manifest declares for the activity, kept as the PlatformDefault. */
    private var manifestOrientation: Int? = null

    private fun applyOrientation() {
        val activity = context as? Activity ?: return
        val declared = manifestOrientation ?: runCatching {
            @Suppress("DEPRECATION")
            activity.packageManager.getActivityInfo(activity.componentName, 0).screenOrientation
        }.getOrDefault(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED).also { manifestOrientation = it }
        val requested = navigationRequestedOrientation(navigationOrientation, declared)
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
    }

    private fun applyControllerChrome() {
        nativeToolbar.visibility = if (headerShown) View.VISIBLE else View.GONE
        nativeToolbar.title = screenTitle
        nativeToolbar.setTitleTextColor(headerTintColor ?: Color.BLACK)
        nativeToolbar.setBackgroundColor(
            if (headerTransparent) Color.TRANSPARENT else headerBackgroundColor ?: Color.WHITE,
        )
        nativeToolbar.elevation = if (headerShadowVisible) dp(4f) else 0f
        if (headerSearchEnabled) {
            val search = nativeSearch ?: SearchView(context).also { view ->
                view.setIconifiedByDefault(false)
                view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String?): Boolean = false
                    override fun onQueryTextChange(newText: String?): Boolean {
                        onSearchChange?.invoke(newText.orEmpty())
                        return true
                    }
                })
                nativeToolbar.addView(view, Toolbar.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                nativeSearch = view
            }
            search.queryHint = headerSearchPlaceholder
            search.visibility = View.VISIBLE
        } else {
            nativeSearch?.visibility = View.GONE
        }
    }

    private fun applyRoutePresentation(route: View) {
        val params = (route.layoutParams as? LayoutParams)
            ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        if (screenPresentation == PRESENTATION_FORM_SHEET) {
            val detents = sheetDetents.ifEmpty { listOf(1f) }.take(3)
            val detent = detents[(sheetInitialDetentIndex - 1).coerceIn(0, detents.lastIndex)]
            params.width = LayoutParams.MATCH_PARENT
            params.height = (height.coerceAtLeast(measuredHeight) * detent.coerceIn(0.05f, 1f))
                .toInt()
                .coerceAtLeast(dp(48f).toInt())
            params.gravity = Gravity.BOTTOM
            route.elevation = dp(12f)
            val radius = dp(if (sheetCornerRadius > 0f) sheetCornerRadius else 20f)
            route.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + radius.toInt(), radius)
                }
            }
            route.clipToOutline = true
        } else {
            params.width = LayoutParams.MATCH_PARENT
            params.height = LayoutParams.MATCH_PARENT
            params.gravity = Gravity.FILL
            route.elevation = 0f
            route.clipToOutline = false
            route.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        route.layoutParams = params
    }

    private fun keepsPreviousRouteVisible(): Boolean = operation != OPERATION_POP &&
        screenPresentation in PRESENTATION_CONTAINED_MODAL..PRESENTATION_FORM_SHEET

    private companion object {
        const val OPERATION_IDLE = 1
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val OPERATION_REPLACE = 4
        const val PREDICTIVE_BACK_VERDICT_TIMEOUT_MS = 1_000L

        const val TRANSITION_PLATFORM_DEFAULT = 1
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_SLIDE_FROM_LEFT = 3
        const val TRANSITION_SLIDE_FROM_BOTTOM = 4
        const val TRANSITION_FADE = 5
        const val TRANSITION_FADE_FROM_BOTTOM = 6
        const val TRANSITION_SCALE = 7
        const val TRANSITION_NONE = 8
        const val TRANSITION_SLIDE_FROM_TOP = 9
        const val TRANSITION_SHARED_AXIS_X = 10
        const val TRANSITION_SHARED_AXIS_Y = 11
        const val TRANSITION_FLIP = 12
        const val TRANSITION_SIMPLE_PUSH = 13

        const val PRESENTATION_CARD = 1
        const val PRESENTATION_CONTAINED_MODAL = 3
        const val PRESENTATION_FORM_SHEET = 7
    }
}

/**
 * `NavigationOrientation` → `ActivityInfo` orientation. PlatformDefault (1)
 * is the orientation the manifest declares (a portrait-locked app stays
 * portrait after a route that freed rotation). All (2) follows the user's
 * auto-rotate setting (FULL_USER): a phone with rotation locked stays put.
 */
internal fun navigationRequestedOrientation(value: Int, manifest: Int): Int = when (value) {
    2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    4 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    5 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    6 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    7 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    8 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> manifest
}
