package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

internal class PamNavigationHost(context: Context) : FrameLayout(context) {
    var operation: Int = OPERATION_IDLE
    var transition: Int = TRANSITION_PLATFORM_DEFAULT
    var durationMs: Long = 240L
    private var revision: Long = 0L
    private var running: ValueAnimator? = null
    private var pendingPreDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var pendingObserver: ViewTreeObserver? = null
    private var gestureEnabled = true
    private var gestureEdgeWidth = 24f
    private var gestureThreshold = 0.35f
    private var onGesturePop: (() -> Unit)? = null
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var velocityTracker: VelocityTracker? = null

    init {
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
        onPop: (() -> Unit)?,
    ) {
        gestureEnabled = enabled
        gestureEdgeWidth = edgeWidth.coerceIn(8f, 96f)
        gestureThreshold = threshold.coerceIn(0.1f, 0.9f)
        onGesturePop = onPop
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!gestureEnabled || childCount < 2 || running != null) return false
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val edge = dp(gestureEdgeWidth)
                val withinEdge = if (rtl) event.x >= width - edge else event.x <= edge
                if (!withinEdge) return false
                gestureStartX = event.x
                gestureTracking = true
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                prepareGesture()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gestureTracking) return false
                val distance = if (rtl) gestureStartX - event.x else event.x - gestureStartX
                if (distance > dp(6f)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gestureTracking) return false
        velocityTracker?.addMovement(event)
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val distance = (if (rtl) gestureStartX - event.x else event.x - gestureStartX)
            .coerceAtLeast(0f)
        val progress = (distance / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyGestureProgress(progress, rtl)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.computeCurrentVelocity(1_000)
                val rawVelocity = velocityTracker?.xVelocity ?: 0f
                val velocity = if (rtl) -rawVelocity else rawVelocity
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
        incoming.visibility = View.VISIBLE
        outgoing.visibility = View.VISIBLE
    }

    private fun applyGestureProgress(progress: Float, rtl: Boolean) {
        val incoming = getChildAt(childCount - 2)
        val outgoing = getChildAt(childCount - 1)
        val sign = if (rtl) -1f else 1f
        outgoing.translationX = sign * width * progress
        incoming.translationX = -sign * width * 0.28f * (1f - progress)
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
                        onGesturePop?.invoke()
                    } else {
                        incoming.visibility = View.INVISIBLE
                        outgoing.visibility = View.VISIBLE
                    }
                }
            })
            start()
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
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

        val actualTransition =
            if (transition == TRANSITION_PLATFORM_DEFAULT) TRANSITION_SLIDE_FROM_RIGHT else transition
        val actualDuration =
            if (durationMs <= 0L || animationsDisabled()) 0L else durationMs.coerceAtMost(2_000L)
        prepare(incoming, outgoing, actualTransition)
        if (actualDuration == 0L || actualTransition == TRANSITION_NONE) {
            finish(incoming, outgoing)
            return
        }

        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        outgoing?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
        }
    }

    private fun finish(incoming: View, outgoing: View?) {
        running = null
        incoming.setLayerType(View.LAYER_TYPE_NONE, null)
        outgoing?.setLayerType(View.LAYER_TYPE_NONE, null)
        reset(incoming)
        outgoing?.let {
            reset(it)
            it.visibility = View.INVISIBLE
        }
        incoming.visibility = View.VISIBLE
        incoming.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        outgoing?.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun showOnlyTop() {
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

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun animationsDisabled(): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

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
    }
}
