package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    /**
     * The route mounted ahead by `Navigator::prewarm()` (always the first
     * child) and the motion of its push, advertised by the navigator for a
     * plain card push: a press declaring the same intent starts that push
     * here, on the frame after the tap ([launchWarm]).
     */
    var warmIntent: String? = null
    var warmTransition: Int = TRANSITION_PLATFORM_DEFAULT
    var warmDurationMs: Long = 0L
    private var revision: Long = 0L
    private var activeRoute: View? = null
    private var movingRoute: View? = null
    private var layeredRoute: View? = null
    /**
     * The route dimmed under a veil ([startVeil]), the view whose opaque
     * background is its backdrop, and the veil drawn over it now.
     */
    private var veilRoute: View? = null
    private var veilBackdrop: View? = null
    private var veilLayerFallback = false
    private var veilAmount = 0f
    private var veilColor = 0
    private val veilPaint = Paint()
    private val backdropRegion = RectF()
    private val siblingBounds = Rect()
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
    /**
     * The tracked touch went down on the leading edge: there the back
     * gesture outranks scrollers and pans under the finger (UIKit's
     * screen-edge pan); only exclusive owners keep it.
     */
    private var gestureFromEdge = false
    /** How a child claimed the tracked touch ([requestDisallowInterceptTouchEvent]). */
    private var childClaim = CHILD_CLAIM_NONE
    /**
     * The arbiter gave the back gesture a touch a list had claimed only for
     * its ScrollView ancestor: onInterceptTouchEvent takes it on this move.
     */
    private var gestureTakeoverPending = false
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

    /**
     * A push of the warm route started natively by a press ([launchWarm]),
     * before the runtime handled that press. The commit of the push adopts
     * the running animation ([runTransition]); a different navigation drops
     * it, and a press that navigated nowhere leaves it unconfirmed: it
     * settles back after [WARM_LAUNCH_VERDICT_TIMEOUT_MS].
     */
    private class WarmLaunch(val incoming: View, val outgoing: View, val kind: Int, val durationMs: Long) {
        var state = WARM_LAUNCH_PENDING
        /** The animation reached the destination before the commit confirmed it. */
        var held = false
    }
    private var warmLaunch: WarmLaunch? = null
    private val warmLaunchTimeout = Runnable { warmLaunch?.let(::rollbackWarmLaunch) }
    private val sharedElements = PamSharedElements(this)
    private val routeCurve = DecelerateInterpolator(1.75f)
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
        if (child === revealing) revealing = null
        warmLaunch?.takeIf { child === it.incoming || child === it.outgoing }?.let(::abortWarmLaunch)
        if (!suppressControllerRemoval) awaitingFirstLayout.remove(child)
        if (suppressControllerRemoval) return
        if (child === veilRoute) clearVeil()
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

    /**
     * A predictive back started. Nothing moves yet: the routes are prepared
     * on the first progress past 0, so a Back that never drags (the Back
     * key, which on Android 15+ also starts a predictive back) leaves every
     * route untouched while the runtime decides whether it pops (a normal
     * pop transition) or is consumed by the app (nothing happens), like
     * React Navigation with a consuming BackHandler.
     */
    fun startPredictiveBack(): Boolean {
        endGestureSettle()
        if (!gestureEnabled || childCount < 2 || running != null || gesturePrepared) return false
        predictiveBackActive = true
        predictiveBackCommitted = false
        predictiveBackProgress = 0f
        return true
    }

    fun updatePredictiveBack(progress: Float) {
        if (!predictiveBackActive) return
        predictiveBackProgress = progress.coerceIn(0f, 1f)
        if (!gesturePrepared) {
            if (predictiveBackProgress <= 0f) return
            prepareGesture()
        }
        applyGestureProgress(
            predictiveBackProgress,
            layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
    }

    fun cancelPredictiveBack() {
        if (!predictiveBackActive) return
        predictiveBackActive = false
        if (!gesturePrepared) {
            predictiveBackProgress = 0f
            return
        }
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
     * settles them back like a cancelled gesture. A Back that never dragged
     * prepared nothing: it is a plain Back, so a pop plays its own
     * transition and a consumed one changes nothing on screen.
     */
    fun commitPredictiveBack() {
        if (!predictiveBackActive) return
        predictiveBackActive = false
        if (!gesturePrepared) {
            predictiveBackProgress = 0f
            return
        }
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
            PamSharedElements.touched(event.rawX, event.rawY)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                childClaim = CHILD_CLAIM_NONE
                gestureTakeoverPending = false
            }
            MotionEvent.ACTION_MOVE -> if (
                gestureTracking && !gesturePrepared && childClaim == CHILD_CLAIM_FOR_SCROLL_ANCESTOR
            ) {
                // A list claimed the touch when it went down only to keep a
                // platform ScrollView off it, so onInterceptTouchEvent no
                // longer runs: the arbiter decides here whether the drag is
                // the list's (it scrolls along this axis) or the back gesture's.
                velocityTracker?.addMovement(event)
                if (gestureBegins(event)) {
                    if (contentOwnsGesture()) {
                        stopTracking()
                    } else {
                        childClaim = CHILD_CLAIM_NONE
                        gestureTakeoverPending = true
                        super.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Tracks how a child claims a touch the back gesture is watching. A
     * claim made on purpose (a scroller or pan that took the drag, a slider,
     * a drawing surface) keeps onInterceptTouchEvent away as usual; a list's
     * claim for its ScrollView ancestor is left to the arbiter.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (gestureTracking && !gesturePrepared) {
            childClaim = when {
                !disallowIntercept -> CHILD_CLAIM_NONE
                PamGestureArbiter.claimingForScrollAncestor && childClaim != CHILD_CLAIM_FIRM ->
                    CHILD_CLAIM_FOR_SCROLL_ANCESTOR
                else -> CHILD_CLAIM_FIRM
            }
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    /** [pressed] (a Pressable) was clicked inside this host's active route. */
    private fun launchFromPress(pressed: View): Boolean {
        val route = activeRoute ?: return false
        if (running != null || gesturePrepared || predictiveBackActive || animationsDisabled()) return false
        return sharedElements.launchFromPress(route, pressed)
    }

    /**
     * [pressed] declared [intent] as its destination and this host holds
     * that route warm (mounted hidden and laid out by `Navigator::prewarm()`):
     * the push starts now, on the frame after the tap, instead of after the
     * runtime handled the press and committed the navigation (~70-150 ms on
     * a mid-range phone). Only a plain card push at rest qualifies: no
     * transition, gesture or navigation pending, no shared element to fly,
     * motion enabled. The route lifecycle (active route, focus, controller
     * state) changes only when the commit confirms the push.
     */
    internal fun launchWarm(intent: String, pressed: View): Boolean {
        // `key@transition`: the press declared the motion it navigates with.
        val at = intent.lastIndexOf('@')
        val key = if (at < 0) intent else intent.substring(0, at)
        val declared = if (at < 0) null else intent.substring(at + 1).toIntOrNull() ?: return false
        if (key.isEmpty() || key != warmIntent || warmLaunch != null) return false
        if (declared != null && resolvedTransition(declared) != resolvedTransition(warmTransition)) return false
        if (running != null || gestureSettle != null || gesturePrepared) return false
        if (predictiveBackActive || predictiveBackAwaiting || pendingPreDraw != null) return false
        if (!isAttachedToWindow || animationsDisabled() || warmDurationMs <= 0L) return false
        if (screenPresentation != PRESENTATION_CARD || childCount < 2) return false
        val incoming = getChildAt(0)
        val outgoing = activeRoute ?: return false
        if (incoming === outgoing || outgoing.parent !== this || incoming in awaitingFirstLayout) return false
        if (!incoming.isLaidOut || incoming.width <= 0 || incoming.height <= 0) return false
        if (routeContaining(pressed) !== outgoing) return false
        val kind = resolvedTransition(warmTransition)
        if (kind == TRANSITION_NONE || sharedElements.wouldFly(incoming, outgoing)) return false
        val duration = warmDurationMs.coerceAtMost(2_000L)
        val launch = WarmLaunch(incoming, outgoing, kind, duration)
        warmLaunch = launch
        // The warm route stays first among the children (the engine's
        // order) but draws, and takes touches, above the others.
        setChildrenDrawingOrderEnabled(true)
        setRouteVisibility(incoming, View.VISIBLE)
        transformedRoutes += incoming
        transformedRoutes += outgoing
        applyProgress(incoming, outgoing, kind, 0f, 0f, popping = false)
        prepareOutgoing(outgoing, kind, popping = false)
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedValue as Float
                applyProgress(incoming, outgoing, kind, routeCurve.getInterpolation(time), time, popping = false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    when (launch.state) {
                        // Held on the destination until the commit (or the timeout) decides.
                        WARM_LAUNCH_PENDING -> launch.held = true
                        WARM_LAUNCH_ADOPTED -> {
                            if (running === animation) running = null
                            finish(incoming, outgoing)
                        }
                        else -> Unit
                    }
                }
            })
            start()
        }
        postDelayed(warmLaunchTimeout, duration + WARM_LAUNCH_VERDICT_TIMEOUT_MS)
        return true
    }

    private fun resolvedTransition(value: Int): Int =
        if (value == TRANSITION_PLATFORM_DEFAULT) TRANSITION_SLIDE_FROM_RIGHT else value

    /** The route (a direct child) that contains [view], or null. */
    private fun routeContaining(view: View): View? {
        var current: View = view
        while (true) {
            val parent = current.parent ?: return null
            if (parent === this) return current
            current = parent as? View ?: return null
        }
    }

    /**
     * The commit of the push a press started: the route becomes active and
     * the animation already running continues (no second start). A commit
     * that asks for another motion (an action transition, no animation)
     * lands on the destination at once.
     */
    private fun adoptWarmLaunch(launch: WarmLaunch) {
        removeCallbacks(warmLaunchTimeout)
        warmLaunch = null
        launch.state = WARM_LAUNCH_ADOPTED
        setChildrenDrawingOrderEnabled(false)
        takePredictiveBackVerdict()
        abortGesture()
        val incoming = launch.incoming
        val outgoing = launch.outgoing
        setActiveRoute(incoming)
        applyRoutePresentation(incoming)
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
        settleReveal()
        val kind = resolvedTransition(transition)
        val animator = running
        val sameMotion = kind == launch.kind && durationMs > 0L && !animationsDisabled()
        when {
            animator != null && animator.isRunning && sameMotion -> Unit
            animator != null && animator.isRunning -> animator.end()
            else -> {
                running = null
                finish(incoming, outgoing)
            }
        }
    }

    /** Drops a native launch at once (another navigation, a removed route, a detach). */
    private fun abortWarmLaunch(launch: WarmLaunch) {
        removeCallbacks(warmLaunchTimeout)
        if (warmLaunch === launch) warmLaunch = null
        launch.state = WARM_LAUNCH_ABORTED
        val animator = running
        running = null
        animator?.cancel()
        settleWarmLaunch(launch)
    }

    /** A launch the app never confirmed (its press navigated nowhere): it slides back. */
    private fun rollbackWarmLaunch(launch: WarmLaunch) {
        if (warmLaunch !== launch) return
        warmLaunch = null
        launch.state = WARM_LAUNCH_ABORTED
        val current = running
        val from = if (launch.held) 1f else (current?.animatedValue as? Float ?: 1f)
        running = null
        current?.cancel()
        if (!isAttachedToWindow || from <= 0f || animationsDisabled()) {
            settleWarmLaunch(launch)
            return
        }
        running = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (launch.durationMs * from).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedValue as Float
                applyProgress(launch.incoming, launch.outgoing, launch.kind, routeCurve.getInterpolation(time), time, popping = false)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (running === animation) running = null
                    settleWarmLaunch(launch)
                }
            })
            start()
        }
    }

    /** The routes of a dropped launch back at rest: the warm route hidden again under the active one. */
    private fun settleWarmLaunch(launch: WarmLaunch) {
        setChildrenDrawingOrderEnabled(false)
        layeredRoute?.setLayerType(View.LAYER_TYPE_NONE, null)
        layeredRoute = null
        reset(launch.incoming)
        reset(launch.outgoing)
        if (launch.incoming.parent === this && launch.incoming !== activeRoute) {
            setRouteVisibility(launch.incoming, View.INVISIBLE)
        }
        restoreIdleRoutes()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val lifted = warmLaunch?.incoming?.let(::indexOfChild) ?: -1
        if (lifted < 0 || lifted >= childCount) return drawingPosition
        return when {
            drawingPosition == childCount - 1 -> lifted
            drawingPosition >= lifted -> drawingPosition + 1
            else -> drawingPosition
        }
    }

    /** True while a press-started push waits for the commit that confirms it (tests). */
    internal fun isLaunchingWarm(): Boolean = warmLaunch != null

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
            val fromEdge = gestureDirection != 2 &&
                if (rtl) event.x >= width - edge else event.x <= edge
            if (!fullScreenGestureEnabled && gestureDirection != 2 && !fromEdge) return false
            gestureFromEdge = fromEdge
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
                if (gestureTakeoverPending) {
                    gestureTakeoverPending = false
                    beginBackGesture()
                    return true
                }
                velocityTracker?.addMovement(event)
                if (gestureBegins(event)) {
                    if (contentOwnsGesture()) {
                        stopTracking()
                        return false
                    }
                    beginBackGesture()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
        return false
    }

    /**
     * Whether the content under the finger owns the drag the back gesture
     * would take (UIKit `require(toFail:)`, `ViewPager.canScroll`): a pager
     * with a previous page, a tab strip scrolled away from its start, a pan
     * or slider that moves that way. From the edge only exclusive owners
     * (a drawing surface, a zoomed photo, `blocksNavigationGesture`) keep
     * it; elsewhere (full-screen or vertical gestures) any owner does.
     */
    private fun contentOwnsGesture(): Boolean {
        if (childCount == 0) return false
        val route = getChildAt(childCount - 1)
        val horizontal = gestureDirection != 2
        val fingerSign = if (horizontal && layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val ownership = PamGestureArbiter.ownershipOfChild(
            this,
            route,
            gestureStartX,
            gestureStartY,
            horizontal,
            fingerSign,
            navigation = true,
        )
        return ownership >= if (gestureFromEdge) PamGestureArbiter.EXCLUSIVE else PamGestureArbiter.DIRECTIONAL
    }

    /** The back gesture takes the touch: its routes move and ancestors keep off it. */
    private fun beginBackGesture() {
        prepareGesture()
        parent?.requestDisallowInterceptTouchEvent(true)
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
                    if (contentOwnsGesture()) {
                        stopTracking()
                        return true
                    }
                    beginBackGesture()
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
        // The route revealed below dims toward the backdrop. One that
        // composites as a group (its alpha would render it offscreen on every
        // frame) takes a veil; a PAM screen applies its alpha to each draw
        // (no offscreen pass, React Native's default) and keeps it.
        if (incoming.hasOverlappingRendering) {
            val vertical = gestureDirection == 2
            startVeil(
                incoming,
                reachX = if (vertical) 0f else width * 0.28f,
                reachY = if (vertical) height * 0.12f else 0f,
                layerFallback = false,
            )
        }
        onGestureStart?.invoke()
    }

    private fun applyGestureProgress(progress: Float, rtl: Boolean) {
        val incoming = gestureIncoming ?: return
        val outgoing = gestureOutgoing ?: return
        val sign = if (rtl) -1f else 1f
        if (!sharedElements.isEmpty && transition == TRANSITION_FADE) {
            // A faded route with a shared element goes back the way it came:
            // it fades while the element follows the finger home.
            outgoing.alpha = 1f - progress
            sharedElements.applyTracking(progress)
            return
        }
        if (gestureDirection == 2) {
            outgoing.translationY = height * progress
            incoming.translationY = -height * 0.12f * (1f - progress)
        } else {
            outgoing.translationX = sign * width * progress
            incoming.translationX = -sign * width * 0.28f * (1f - progress)
        }
        dimRoute(incoming, 0.18f * (1f - progress))
        sharedElements.applyTracking(progress)
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
        // A predictive back that never dragged moved no route.
        val wasPrepared = gesturePrepared
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
        warmLaunch?.let(::abortWarmLaunch)
        abortGesture()
        clearSharedElements()
        clearPendingTransition()
        revealing = null
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

    /**
     * The route the scheduled transition shows on its next frame: mounted
     * hidden and laid out first, it is not covered for media
     * ([PamRouteCoverage.isCovered] with `revealing`). Its players are
     * created and prepare while it lays out, a frame (and that layout)
     * before it shows, instead of when the transition starts.
     */
    private var revealing: View? = null

    internal fun isRevealing(route: View): Boolean = revealing === route

    private fun reveal(route: View) {
        if (revealing === route) return
        settleReveal()
        revealing = route
        PamRouteCoverage.dispatchChanged(route)
    }

    /** Ends a reveal: a route the transition did not show is covered again (its media release). */
    private fun settleReveal() {
        val route = revealing ?: return
        revealing = null
        if (route.parent === this && route.visibility != View.VISIBLE) PamRouteCoverage.dispatchChanged(route)
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
        // The push a press already started natively: its commit adopts the
        // running animation; any other navigation drops it first.
        warmLaunch?.let { launch ->
            val top = if (childCount > 0) getChildAt(childCount - 1) else null
            if (operation == OPERATION_PUSH && top === launch.incoming && launch.outgoing.parent === this) {
                adoptWarmLaunch(launch)
                return
            }
            abortWarmLaunch(launch)
        }
        // A navigation interrupts whatever still moves the routes: the
        // previous transition ends on its destination and a back gesture in
        // progress (or settling) is dropped, so no route keeps its frame.
        running?.cancel()
        takePredictiveBackVerdict()
        abortGesture()
        if (childCount == 0) {
            settleReveal()
            restoreIdleRoutes()
            return
        }
        val top = getChildAt(childCount - 1)
        if (operation != OPERATION_POP && top in awaitingFirstLayout) {
            // Mounted in this frame: lay it out on the next one, then show it
            // (mount and first layout never share a frame).
            releaseFirstLayout(top)
            reveal(top)
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
                settleReveal()
                restoreIdleRoutes()
                return
            }
        }
        if (incoming in awaitingFirstLayout) {
            releaseFirstLayout(incoming)
            reveal(incoming)
            scheduleTransition()
            return
        }
        setActiveRoute(incoming)
        applyRoutePresentation(incoming)

        if (predictiveBackCommitted && operation == OPERATION_POP) {
            predictiveBackCommitted = false
            predictiveBackProgress = 0f
            finish(incoming, outgoing)
            settleReveal()
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
        // Only now: the incoming route is visible, so its media never see it covered in between.
        settleReveal()

        prepareSharedElements(incoming, outgoing, popping = operation == OPERATION_POP)

        val actualTransition =
            if (transition == TRANSITION_PLATFORM_DEFAULT) TRANSITION_SLIDE_FROM_RIGHT else transition
        val sharedDuration = sharedElements.durationMs
        val actualDuration = if (durationMs <= 0L || animationsDisabled()) {
            0L
        } else {
            // A shared element sets the pace: the route moves in step with it.
            (if (sharedElements.isEmpty) durationMs else sharedDuration).coerceAtMost(2_000L)
        }
        transformedRoutes += incoming
        outgoing?.let { transformedRoutes += it }
        prepare(incoming, outgoing, actualTransition)
        if (actualDuration == 0L || actualTransition == TRANSITION_NONE) {
            finish(incoming, outgoing)
            return
        }

        outgoing?.let { prepareOutgoing(it, actualTransition, popping = operation == OPERATION_POP) }
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = actualDuration
            // Linear time: the routes take the platform deceleration, every
            // shared element its own curve (a spring in, an ease-in home).
            interpolator = LinearInterpolator()
            addUpdateListener {
                val time = it.animatedValue as Float
                applyProgress(incoming, outgoing, actualTransition, routeCurve.getInterpolation(time), time)
            }
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
        applyProgress(incoming, outgoing, kind, 0f, 0f)
    }

    private fun applyProgress(
        incoming: View,
        outgoing: View?,
        kind: Int,
        progress: Float,
        time: Float,
        popping: Boolean = operation == OPERATION_POP,
    ) {
        val width = width.coerceAtLeast(1).toFloat()
        val height = height.coerceAtLeast(1).toFloat()
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val semanticSign = if (rtl) -1f else 1f
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
                outgoing?.let { dimRoute(it, progress * 0.18f) }
            }
            TRANSITION_SLIDE_FROM_BOTTOM -> {
                if (popping) outgoing?.translationY = height * progress
                else incoming.translationY = height * (1f - progress)
                outgoing?.let { dimRoute(it, progress * 0.12f) }
            }
            TRANSITION_SLIDE_FROM_TOP -> {
                if (popping) outgoing?.translationY = -height * progress
                else incoming.translationY = -height * (1f - progress)
                outgoing?.let { dimRoute(it, progress * 0.12f) }
            }
            TRANSITION_FADE -> if (sharedElements.isEmpty) {
                incoming.alpha = progress
                outgoing?.alpha = 1f - progress
            } else if (popping) {
                // With a shared element only the top route fades, in step
                // with the flight: the screen below stays put while the
                // element flies home.
                outgoing?.alpha = 1f - sharedElements.eased(time).coerceIn(0f, 1f)
            } else {
                // A launch backdrop already darkened the screen: continue from it.
                val floor = sharedElements.fadeFloor
                incoming.alpha = floor + (1f - floor) * sharedElements.eased(time).coerceIn(0f, 1f)
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
        sharedElements.apply(time)
    }

    /**
     * How [outgoing] is dimmed through a transition. A slide only darkens it
     * toward the backdrop (18% horizontal, 12% vertical) under the incoming
     * route: a veil when that backdrop is one known opaque color
     * ([startVeil]). Anything else (a fade to 0, a pop, where the outgoing
     * route fades over the incoming one, an unknown backdrop) draws it once
     * into a hardware layer and composites that, instead of re-rendering it
     * offscreen for its alpha on every frame.
     */
    private fun prepareOutgoing(outgoing: View, kind: Int, popping: Boolean) {
        val reachX = when (kind) {
            TRANSITION_SLIDE_FROM_RIGHT, TRANSITION_SLIDE_FROM_LEFT -> width * (if (popping) 1f else 0.28f)
            else -> 0f
        }
        val reachY = when (kind) {
            TRANSITION_SLIDE_FROM_BOTTOM, TRANSITION_SLIDE_FROM_TOP -> if (popping) height.toFloat() else 0f
            else -> 0f
        }
        val slides = kind == TRANSITION_SLIDE_FROM_RIGHT || kind == TRANSITION_SLIDE_FROM_LEFT ||
            kind == TRANSITION_SLIDE_FROM_BOTTOM || kind == TRANSITION_SLIDE_FROM_TOP
        if (slides && startVeil(outgoing, reachX, reachY, layerFallback = true)) return
        if (outgoing.layerType == View.LAYER_TYPE_NONE) {
            outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layeredRoute = outgoing
        }
    }

    /**
     * Dims [route] by [amount]: alpha `1 - amount`, composited as one
     * picture. Under a veil the route keeps alpha 1 and the host draws the
     * backdrop color over it at [amount] instead: the same pixels.
     */
    private fun dimRoute(route: View, amount: Float) {
        if (route === veilRoute) {
            val color = veilBackdrop?.let(::backgroundFill)
            if (color != null && Color.alpha(color) == 255) {
                route.alpha = 1f
                if (veilColor != color || veilAmount != amount) {
                    veilColor = color
                    veilAmount = amount
                    invalidate()
                }
                return
            }
            // The backdrop stopped being one opaque color (a background
            // changed mid-transition): group opacity again.
            val layer = veilLayerFallback
            clearVeil()
            if (layer && layeredRoute == null && route.layerType == View.LAYER_TYPE_NONE) {
                route.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                layeredRoute = route
            }
        }
        route.alpha = 1f - amount
    }

    /**
     * Dims [route] under a veil from now on, when the one opaque color
     * behind it is known ([backdropOf]): the route keeps alpha 1 and the
     * host draws that color over it at the dim amount ([drawChild]: right
     * after the route, under every route drawn after it). Over an opaque
     * backdrop B both give every pixel `(1 - d)·route + d·B`, but group
     * opacity renders the route offscreen: a hardware layer whose first
     * paint cost a 30 ms frame on a Galaxy S10 at every push (and a
     * full-screen texture while it lives). The veil is one rect.
     * [reachX]/[reachY]: how far the route moves from its place while it is
     * dimmed. [layerFallback]: a backdrop that stops being opaque midway
     * gives the route a hardware layer (a transition); otherwise it keeps a
     * plain alpha. False when the backdrop is not known: nothing changed.
     */
    private fun startVeil(route: View, reachX: Float, reachY: Float, layerFallback: Boolean): Boolean {
        clearVeil()
        val backdrop = backdropOf(route, reachX, reachY) ?: return false
        veilRoute = route
        veilBackdrop = backdrop
        veilLayerFallback = layerFallback
        return true
    }

    private fun clearVeil() {
        val drawn = veilRoute != null && veilAmount > 0f
        veilRoute = null
        veilBackdrop = null
        veilAmount = 0f
        if (drawn) invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val more = super.drawChild(canvas, child, drawingTime)
        if (child === veilRoute && veilAmount > 0f && child.visibility == View.VISIBLE) drawVeil(canvas, child)
        return more
    }

    /** The veil over [route]: its box, moved with it, in the backdrop color at the dim amount. */
    private fun drawVeil(canvas: Canvas, route: View) {
        val checkpoint = canvas.save()
        canvas.translate(route.left.toFloat(), route.top.toFloat())
        val matrix = route.matrix
        if (!matrix.isIdentity) canvas.concat(matrix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A float alpha: the very blend factor a route alpha uses.
            veilPaint.setColor(
                Color.pack(
                    Color.red(veilColor) / 255f,
                    Color.green(veilColor) / 255f,
                    Color.blue(veilColor) / 255f,
                    veilAmount,
                ),
            )
        } else {
            veilPaint.color = (veilColor and 0x00FFFFFF) or ((veilAmount * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24)
        }
        canvas.drawRect(0f, 0f, route.width.toFloat(), route.height.toFloat(), veilPaint)
        canvas.restoreToCount(checkpoint)
    }

    /**
     * The view whose background is the one opaque color under every pixel
     * [route] covers while it moves up to [reachX]/[reachY] px from its
     * place, when nothing else can show there: no visible route drawn
     * before it, no sibling (or outer shadow) drawn before an ancestor in
     * that area, no ancestor painting more than a plain fill or casting an
     * elevation shadow, and the first fill met on the way up to the
     * window opaque and covering the area. Null when any of that is not
     * known (a translucent window, a picture, gradient, border or ripple
     * background, a blur, a shimmer).
     */
    internal fun backdropOf(route: View, reachX: Float, reachY: Float): View? {
        if (route.parent !== this || route.visibility != View.VISIBLE) return null
        if (route.z != 0f || route.clipToOutline || PamBoxShadows.has(route)) return null
        val region = backdropRegion
        region.set(-reachX, -reachY, route.width + reachX, route.height + reachY)
        if (!clipToWindow(route, region)) return null
        region.offset(route.left.toFloat(), route.top.toFloat())
        if (drawnUnder(this, route, region)) return null
        var view: View = this
        while (true) {
            // [region] is in [view]'s content coordinates.
            region.offset(-view.scrollX.toFloat(), -view.scrollY.toFloat())
            val parent = view.parent
            if (parent !is ViewGroup || parent.clipChildren) {
                if (!region.intersect(0f, 0f, view.width.toFloat(), view.height.toFloat())) return null
            }
            val fill = backgroundFill(view) ?: return null
            if (Color.alpha(fill) != 0) {
                val covered = region.left >= 0f && region.top >= 0f &&
                    region.right <= view.width && region.bottom <= view.height
                return view.takeIf { covered && Color.alpha(fill) == 255 }
            }
            if (parent !is ViewGroup) return null
            if (!view.willNotDraw() || (view as? PamContainer)?.paintsUnderChildren() == true) return null
            if (view.z != 0f || PamBoxShadows.has(view)) return null
            val matrix = view.matrix
            if (!matrix.isIdentity) matrix.mapRect(region)
            region.offset(view.left.toFloat(), view.top.toFloat())
            if (drawnUnder(parent, view, region)) return null
            view = parent
        }
    }

    /**
     * Limits [region] (in [route]'s coordinates) to the part the window
     * shows. Left whole under a scaled or rotated ancestor. False when
     * nothing of it is on screen.
     */
    private fun clipToWindow(route: View, region: RectF): Boolean {
        var x = route.left.toFloat()
        var y = route.top.toFloat()
        var view: View = this
        while (true) {
            if (view.scaleX != 1f || view.scaleY != 1f || view.rotation != 0f ||
                view.rotationX != 0f || view.rotationY != 0f
            ) {
                return true
            }
            x += view.left + view.translationX - view.scrollX
            y += view.top + view.translationY - view.scrollY
            view = view.parent as? View ?: break
        }
        return region.intersect(-x, -y, view.width - x, view.height - y)
    }

    /** Whether a sibling of [view] drawn before it, or its outer shadow, may show in [region] ([parent]'s coordinates). */
    private fun drawnUnder(parent: ViewGroup, view: View, region: RectF): Boolean {
        val host = parent as? PamNavigationHost
        val position = parent.indexOfChild(view).let { host?.drawPosition(it) ?: it }
        for (index in 0 until parent.childCount) {
            val sibling = parent.getChildAt(index)
            if (sibling === view || sibling.visibility != View.VISIBLE || sibling.alpha <= 0f) continue
            val order = host?.drawPosition(index) ?: index
            if (sibling.z > view.z || (sibling.z == view.z && order > position)) continue
            if (PamBoxShadows.has(sibling)) return true
            sibling.getHitRect(siblingBounds)
            if (region.intersects(
                    siblingBounds.left.toFloat(),
                    siblingBounds.top.toFloat(),
                    siblingBounds.right.toFloat(),
                    siblingBounds.bottom.toFloat(),
                )
            ) {
                return true
            }
        }
        return false
    }

    /** Where the child at [index] draws among the children ([getChildDrawingOrder]'s inverse). */
    private fun drawPosition(index: Int): Int {
        val lifted = warmLaunch?.incoming?.let(::indexOfChild) ?: -1
        return when {
            lifted < 0 || index < lifted -> index
            index == lifted -> childCount - 1
            else -> index - 1
        }
    }

    /**
     * The color [view]'s background paints over its whole box (alpha 0 when
     * it paints nothing), or null when it is not one known color: a
     * picture, a gradient, a border, rounded corners, a ripple, a tint.
     */
    private fun backgroundFill(view: View): Int? {
        val background = view.background ?: return Color.TRANSPARENT
        if (view.backgroundTintList != null || background.colorFilter != null) return null
        return when {
            background is ColorDrawable -> background.color
            background is GradientDrawable && view.getTag(dev.pam.nativeapp.R.id.pam_plain_fill) === background -> {
                val colors = background.color ?: return null
                val color = colors.getColorForState(background.state, colors.defaultColor)
                Color.argb(
                    Color.alpha(color) * background.alpha / 255,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color),
                )
            }
            else -> null
        }
    }

    /** The route dimmed under a veil, the veil's color and its amount (tests). */
    internal fun veiledRoute(): View? = veilRoute

    internal fun veilColor(): Int = veilColor

    internal fun veilAmount(): Float = veilAmount

    /** The route drawn into a hardware layer for the running transition (tests). */
    internal fun layeredRoute(): View? = layeredRoute

    /** The animator of the running transition (tests pause and seek it). */
    internal fun transitionAnimator(): ValueAnimator? = running

    private fun prepareSharedElements(incoming: View, outgoing: View?, popping: Boolean = true) {
        sharedElements.clear()
        if (outgoing == null || animationsDisabled() || durationMs <= 0L) return
        sharedElements.prepare(incoming, outgoing, durationMs, popping)
    }

    private fun clearSharedElements() {
        sharedElements.clear()
    }

    internal fun activeSharedElementCount(): Int = sharedElements.count

    internal fun activeSharedElementFrames(): List<FloatArray> = sharedElements.frames()

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
        val previous = activeRoute
        activeRoute = view
        if (previous != null) releaseInputOutside(view)
        updateControllerLifecycles()
        onActiveRouteChanged?.invoke()
    }

    /**
     * React Navigation blurs the focused TextInput and dismisses the keyboard
     * whenever the focused route changes: an input of the route left behind
     * (a search field under a pushed profile or a story viewer) must not keep
     * the IME over the new route, nor receive its Back key. An input of the
     * incoming route (autoFocus) keeps the focus and the keyboard.
     */
    private fun releaseInputOutside(route: View) {
        val focused = rootView?.findFocus() as? EditText ?: return
        if (isDescendantOf(focused, route) || !isDescendantOf(focused, this)) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(focused.windowToken, 0)
        // The root absorbs the focus (see PamRootHost.blurInputAfterImeHidden);
        // clearing it alone would make Android refocus the first input.
        val root = generateSequence(parent) { it.parent }.filterIsInstance<PamRootHost>().firstOrNull()
        if (root != null) {
            root.isFocusableInTouchMode = true
            if (!root.requestFocus()) focused.clearFocus()
            root.isFocusableInTouchMode = false
        } else {
            focused.clearFocus()
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
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
        if (view === veilRoute) clearVeil()
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

    internal companion object Launcher {
        /** Starts a launch flight in the innermost host around [pressed]. */
        fun pressed(pressed: View) {
            var current = pressed.parent
            while (current != null) {
                if (current is PamNavigationHost) {
                    if (current.launchFromPress(pressed)) return
                }
                current = current.parent
            }
            val intent = (pressed as? PamPressable)?.navigationIntent?.takeIf(String::isNotEmpty) ?: return
            current = pressed.parent
            while (current != null) {
                if (current is PamNavigationHost && current.launchWarm(intent, pressed)) return
                current = current.parent
            }
        }

        const val OPERATION_IDLE = 1
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val OPERATION_REPLACE = 4
        const val PREDICTIVE_BACK_VERDICT_TIMEOUT_MS = 1_000L
        /** How long a press-started push waits, after its animation, for the commit that confirms it. */
        const val WARM_LAUNCH_VERDICT_TIMEOUT_MS = 1_000L
        private const val WARM_LAUNCH_PENDING = 0
        private const val WARM_LAUNCH_ADOPTED = 1
        private const val WARM_LAUNCH_ABORTED = 2


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

        private const val CHILD_CLAIM_NONE = 0
        /** A list's claim for its ScrollView ancestor (axis unknown): the arbiter decides. */
        private const val CHILD_CLAIM_FOR_SCROLL_ANCESTOR = 1
        /** A child took the touch on purpose. */
        private const val CHILD_CLAIM_FIRM = 2
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
