package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Outline
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SearchView
import android.widget.Toolbar
import android.graphics.Color
import dev.pam.nativeapp.R
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
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureDirection = 1
    private var fullScreenGestureEnabled = false
    private var velocityTracker: VelocityTracker? = null
    private var predictiveBackActive = false
    private var predictiveBackCommitted = false
    private var predictiveBackProgress = 0f
    private data class SharedElementAnimation(
        val snapshot: ImageView,
        val bitmap: Bitmap,
        val source: View,
        val destination: View,
        val deltaX: Float,
        val deltaY: Float,
        val scaleX: Float,
        val scaleY: Float,
    )
    private val sharedElements = ArrayList<SharedElementAnimation>(4)
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

    fun insert(view: View, index: Int) {
        val isInitialRoute = childCount == 0
        view.visibility = if (isInitialRoute) View.VISIBLE else View.INVISIBLE
        view.importantForAccessibility = if (isInitialRoute) {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        addView(
            view,
            index.coerceIn(0, childCount),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        if (isAttachedToWindow) ensureRouteController(view)
        if (isInitialRoute) setActiveRoute(view)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        childrenSnapshot().forEach(::ensureRouteController)
        updateControllerLifecycles()
        overlay.add(nativeToolbar)
        applyControllerChrome()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        nativeToolbar.layout(0, 0, width, dp(56f).toInt())
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        if (suppressControllerRemoval) return
        val fragment = routeControllers.remove(child) ?: return
        fragmentManager()?.takeUnless(FragmentManager::isStateSaved)?.let { manager ->
            manager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
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

    fun navigate(nextRevision: Long) {
        if (nextRevision == revision) return
        revision = nextRevision
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
        if (!gestureEnabled || childCount < 2 || running != null) return false
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

    fun commitPredictiveBack() {
        if (!predictiveBackActive) return
        predictiveBackActive = false
        predictiveBackCommitted = true
        predictiveBackProgress = 1f
        applyGestureProgress(1f, layoutDirection == View.LAYOUT_DIRECTION_RTL)
        onGestureEnd?.invoke()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!gestureEnabled || childCount < 2 || running != null) return false
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edge = dp(gestureEdgeWidth)
                val withinEdge = fullScreenGestureEnabled || gestureDirection == 2 ||
                    if (rtl) event.x >= width - edge else event.x <= edge
                if (!withinEdge) return false
                gestureStartX = event.x
                gestureStartY = event.y
                gestureTracking = true
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                prepareGesture()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureTracking) return false
                val distance = if (gestureDirection == 2) event.y - gestureStartY
                    else if (rtl) gestureStartX - event.x else event.x - gestureStartX
                if (distance > dp(6f)) return true
            }
        }
        return false
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
            MotionEvent.ACTION_MOVE -> applyGestureProgress(progress, rtl)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.computeCurrentVelocity(1_000)
                val rawVelocity = if (gestureDirection == 2) velocityTracker?.yVelocity ?: 0f
                    else velocityTracker?.xVelocity ?: 0f
                val velocity = if (gestureDirection == 2 || !rtl) rawVelocity else -rawVelocity
                val complete = event.actionMasked == MotionEvent.ACTION_UP &&
                    (progress >= gestureThreshold || velocity >= dp(700f))
                settleGesture(progress, complete, rtl)
                gestureTracking = false
                velocityTracker?.recycle()
                velocityTracker = null
                if (event.actionMasked == MotionEvent.ACTION_UP && progress == 0f) {
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
        setActiveRoute(incoming)
        applyRoutePresentation(incoming)
        incoming.visibility = View.VISIBLE
        outgoing.visibility = View.VISIBLE
        onGestureStart?.invoke()
    }

    private fun applyGestureProgress(progress: Float, rtl: Boolean) {
        val incoming = getChildAt(childCount - 2)
        val outgoing = getChildAt(childCount - 1)
        val sign = if (rtl) -1f else 1f
        if (gestureDirection == 2) {
            outgoing.translationY = height * progress
            incoming.translationY = -height * 0.12f * (1f - progress)
        } else {
            outgoing.translationX = sign * width * progress
            incoming.translationX = -sign * width * 0.28f * (1f - progress)
        }
        incoming.alpha = 0.82f + 0.18f * progress
    }

    private fun settleGesture(start: Float, complete: Boolean, rtl: Boolean) {
        ValueAnimator.ofFloat(start, if (complete) 1f else 0f).apply {
            duration = ((if (complete) 1f - start else start) * 220)
                .toLong()
                .coerceAtLeast(80)
            interpolator = DecelerateInterpolator(1.75f)
            addUpdateListener { applyGestureProgress(it.animatedValue as Float, rtl) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (childCount < 2) return
                    val incoming = getChildAt(childCount - 2)
                    val outgoing = getChildAt(childCount - 1)
                    reset(incoming)
                    reset(outgoing)
                    if (complete) {
                        outgoing.visibility = View.INVISIBLE
                        incoming.visibility = View.VISIBLE
                        setActiveRoute(incoming)
                        onGestureEnd?.invoke()
                        onGesturePop?.invoke()
                    } else {
                        incoming.visibility = View.INVISIBLE
                        outgoing.visibility = View.VISIBLE
                        setActiveRoute(outgoing)
                        onGestureCancel?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        overlay.remove(nativeToolbar)
        clearSharedElements()
        clearPendingTransition()
        running?.cancel()
        running = null
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
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
        running?.cancel()
        if (childCount == 0) return

        val outgoing: View?
        val incoming: View
        when (operation) {
            OPERATION_PUSH, OPERATION_REPLACE -> {
                incoming = getChildAt(childCount - 1)
                outgoing = if (childCount > 1) getChildAt(childCount - 2) else null
            }
            OPERATION_POP -> {
                incoming = getChildAt((childCount - 2).coerceAtLeast(0))
                outgoing = if (childCount > 1) getChildAt(childCount - 1) else null
            }
            else -> {
                showOnlyTop()
                return
            }
        }
        setActiveRoute(incoming)

        if (predictiveBackCommitted && operation == OPERATION_POP) {
            predictiveBackCommitted = false
            predictiveBackProgress = 0f
            finish(incoming, outgoing)
            return
        }

        for (index in 0 until childCount) {
            getChildAt(index).apply {
                visibility = if (this === incoming || this === outgoing) View.VISIBLE else View.INVISIBLE
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
        val actualDuration =
            if (durationMs <= 0L || animationsDisabled()) 0L else durationMs.coerceAtMost(2_000L)
        prepare(incoming, outgoing, actualTransition)
        if (actualDuration == 0L || actualTransition == TRANSITION_NONE) {
            finish(incoming, outgoing)
            return
        }

        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = actualDuration
            interpolator = DecelerateInterpolator(1.75f)
            addUpdateListener { applyProgress(incoming, outgoing, actualTransition, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish(incoming, outgoing)
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
            TRANSITION_FADE -> {
                incoming.alpha = progress
                outgoing?.alpha = 1f - progress
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
        clearSharedElements()
        if (outgoing == null || animationsDisabled() || durationMs <= 0L) return
        val sources = sharedElementViews(outgoing)
        val destinations = sharedElementViews(incoming)
        sources.entries.take(16).forEach { (tag, source) ->
            val destination = destinations[tag] ?: return@forEach
            if (source.width <= 0 || source.height <= 0 || destination.width <= 0 || destination.height <= 0) {
                return@forEach
            }
            val bitmap = runCatching {
                Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                    source.draw(Canvas(it))
                }
            }.getOrNull() ?: return@forEach
            val start = descendantRect(source)
            val end = descendantRect(destination)
            val snapshot = ImageView(context).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_XY
                pivotX = 0f
                pivotY = 0f
                layout(0, 0, source.width, source.height)
                x = start.left.toFloat()
                y = start.top.toFloat()
            }
            source.visibility = View.INVISIBLE
            destination.visibility = View.INVISIBLE
            overlay.add(snapshot)
            sharedElements += SharedElementAnimation(
                snapshot = snapshot,
                bitmap = bitmap,
                source = source,
                destination = destination,
                deltaX = (end.left - start.left).toFloat(),
                deltaY = (end.top - start.top).toFloat(),
                scaleX = end.width().toFloat() / source.width,
                scaleY = end.height().toFloat() / source.height,
            )
        }
    }

    private fun sharedElementViews(root: View): Map<String, View> {
        val result = LinkedHashMap<String, View>()
        fun visit(view: View) {
            (view.getTag(R.id.pam_shared_transition_tag) as? String)
                ?.takeIf { it.isNotEmpty() && !result.containsKey(it) }
                ?.let { result[it] = view }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun descendantRect(view: View): Rect = Rect(0, 0, view.width, view.height).also {
        offsetDescendantRectToMyCoords(view, it)
    }

    private fun applySharedElementProgress(progress: Float) {
        sharedElements.forEach { item ->
            item.snapshot.translationX = item.deltaX * progress
            item.snapshot.translationY = item.deltaY * progress
            item.snapshot.scaleX = 1f + (item.scaleX - 1f) * progress
            item.snapshot.scaleY = 1f + (item.scaleY - 1f) * progress
        }
    }

    private fun clearSharedElements() {
        sharedElements.forEach { item ->
            item.source.visibility = View.VISIBLE
            item.destination.visibility = View.VISIBLE
            overlay.remove(item.snapshot)
            item.snapshot.setImageDrawable(null)
            if (!item.bitmap.isRecycled) item.bitmap.recycle()
        }
        sharedElements.clear()
    }

    private fun finish(incoming: View, outgoing: View?) {
        clearSharedElements()
        running = null
        setActiveRoute(incoming)
        reset(incoming)
        outgoing?.let {
            reset(it)
            it.visibility = if (keepsPreviousRouteVisible()) View.VISIBLE else View.INVISIBLE
        }
        incoming.visibility = View.VISIBLE
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        outgoing?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onTransitionEnd?.invoke()
    }

    private fun showOnlyTop() {
        val top = if (childCount == 0) null else getChildAt(childCount - 1)
        top?.let(::setActiveRoute)
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                val active = index == childCount - 1
                visibility = if (active) View.VISIBLE else View.INVISIBLE
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
        val fragment = PamRouteFragment().also { it.bind(view) }
        routeControllers[view] = fragment
        suppressControllerRemoval = true
        if (view.parent === this) removeView(view)
        suppressControllerRemoval = false
        manager.beginTransaction()
            .setReorderingAllowed(true)
            .add(id, fragment, "pam-route-${id}-${nextControllerId++}")
            .setMaxLifecycle(
                fragment,
                if (view === activeRoute) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
            )
            .commitNow()
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

    private fun animationsDisabled(): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

    private fun applyOrientation() {
        val activity = context as? Activity ?: return
        val requested = when (navigationOrientation) {
            2 -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            4 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            5 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            6 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            7 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            8 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
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
