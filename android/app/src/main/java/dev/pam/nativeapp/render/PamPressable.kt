package dev.pam.nativeapp.render

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar

internal data class PamPressPointer(
    val x: Float,
    val y: Float,
    val pageX: Float,
    val pageY: Float,
    val timestamp: Long,
    val pointerId: Int,
)

internal class PamPressable(context: Context) : PamContainer(context) {
    private val gestureRecognizer = PamGestureRecognizer(this)
    private var onPress: (() -> Unit)? = null
    private var localOnPress: (() -> Unit)? = null
    private var onLongPress: (() -> Unit)? = null
    private var onPressIn: ((PamPressPointer) -> Unit)? = null
    private var onPressOut: ((PamPressPointer) -> Unit)? = null
    private var onPressMove: ((PamPressPointer) -> Unit)? = null
    private var pressOpacity = DEFAULT_PRESS_OPACITY
    private var pressScale = 1f
    private var targetOpacity = 1f
    private var targetScaleX = 1f
    private var targetScaleY = 1f
    private var delayLongPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private var delayPressInMs = 0L
    private var delayPressOutMs = 0L
    private var retentionLeft = DEFAULT_RETENTION_HORIZONTAL
    private var retentionTop = DEFAULT_RETENTION_HORIZONTAL
    private var retentionRight = DEFAULT_RETENTION_HORIZONTAL
    private var retentionBottom = DEFAULT_RETENTION_BOTTOM
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureActive = false
    private var eligibleForPress = false
    private var pressInDispatched = false
    private var longPressDispatched = false
    private var lastPointer = PamPressPointer(0f, 0f, 0f, 0f, 0L, 0)
    private var pendingMove: PamPressPointer? = null
    private var moveScheduled = false
    private var nativeTransformEnabled = false
    private var nativeMinScale = 1f
    private var nativeMaxScale = 4f
    private var nativeResetKey = 0L
    private var nativeBaseTranslationX = 0f
    private var nativeBaseTranslationY = 0f
    private var nativeBaseScaleX = 1f
    private var nativeBaseScaleY = 1f
    private var nativeBaseRotation = 0f

    private val pressInRunnable = Runnable {
        if (gestureActive && eligibleForPress) {
            emitPressIn(lastPointer)
        }
    }
    private val longPressRunnable = Runnable {
        if (!gestureActive || !eligibleForPress || longPressDispatched) return@Runnable
        emitPressIn(lastPointer)
        longPressDispatched = performLongClick()
    }
    private val pressOutRunnable = Runnable {
        if (pressInDispatched) {
            pressInDispatched = false
            isPressed = false
            animate()
                .alpha(targetOpacity)
                .scaleX(targetScaleX)
                .scaleY(targetScaleY)
                .setDuration(PRESS_OUT_ANIMATION_MS)
                .start()
            onPressOut?.invoke(lastPointer)
        }
    }
    private val moveRunnable = Runnable {
        moveScheduled = false
        val pointer = pendingMove ?: return@Runnable
        pendingMove = null
        if (gestureActive) {
            onPressMove?.invoke(pointer)
        }
    }

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        isFocusableInTouchMode = false
        defaultFocusHighlightEnabled = true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        if (!enabled && hasFocus()) clearFocus()
    }

    fun configure(
        pressOpacity: Float,
        pressScale: Float,
        targetOpacity: Float,
        targetScaleX: Float,
        targetScaleY: Float,
        delayLongPressMs: Long,
        delayPressInMs: Long,
        delayPressOutMs: Long,
        retentionLeft: Float,
        retentionTop: Float,
        retentionRight: Float,
        retentionBottom: Float,
        androidDisableSound: Boolean,
    ) {
        this.pressOpacity = pressOpacity.coerceIn(0f, 1f)
        this.pressScale = pressScale.coerceIn(0.01f, 4f)
        this.targetOpacity = targetOpacity.coerceIn(0f, 1f)
        this.targetScaleX = targetScaleX
        this.targetScaleY = targetScaleY
        this.delayLongPressMs = delayLongPressMs.coerceIn(0L, MAX_PRESS_DELAY_MS)
        this.delayPressInMs = delayPressInMs.coerceIn(0L, MAX_PRESS_DELAY_MS)
        this.delayPressOutMs = delayPressOutMs.coerceIn(0L, MAX_PRESS_DELAY_MS)
        this.retentionLeft = retentionLeft.coerceAtLeast(0f)
        this.retentionTop = retentionTop.coerceAtLeast(0f)
        this.retentionRight = retentionRight.coerceAtLeast(0f)
        this.retentionBottom = retentionBottom.coerceAtLeast(0f)
        isSoundEffectsEnabled = !androidDisableSound
    }

    fun setCallbacks(
        onPress: (() -> Unit)?,
        onLongPress: (() -> Unit)?,
        onPressIn: ((PamPressPointer) -> Unit)?,
        onPressOut: ((PamPressPointer) -> Unit)?,
        onPressMove: ((PamPressPointer) -> Unit)?,
    ) {
        this.onPress = onPress
        this.onLongPress = onLongPress
        this.onPressIn = onPressIn
        this.onPressOut = onPressOut
        this.onPressMove = onPressMove
        isLongClickable = onLongPress != null
        updateClickable()
    }

    fun setLocalOnPress(callback: (() -> Unit)?) {
        localOnPress = callback
        updateClickable()
    }

    fun configureGesture(
        config: PamGestureConfig?,
        callback: ((PamGesturePayload) -> Unit)?,
        nativeTransform: Boolean = false,
        nativeMinScale: Float = 1f,
        nativeMaxScale: Float = 4f,
        nativeResetKey: Long = 0L,
    ) {
        this.nativeTransformEnabled = nativeTransform
        this.nativeMinScale = nativeMinScale.coerceAtLeast(0.01f)
        this.nativeMaxScale = nativeMaxScale.coerceAtLeast(this.nativeMinScale)
        if (this.nativeResetKey != nativeResetKey) {
            this.nativeResetKey = nativeResetKey
            resetNativeTransform()
        }
        gestureRecognizer.configure(config) { payload ->
            applyNativeTransform(payload)
            callback?.invoke(payload)
        }
        updateClickable()
    }

    private fun applyNativeTransform(payload: PamGesturePayload) {
        if (!nativeTransformEnabled) return
        val child = getChildAt(0) ?: return
        if (payload.state == 1) {
            nativeBaseTranslationX = child.translationX
            nativeBaseTranslationY = child.translationY
            nativeBaseScaleX = child.scaleX
            nativeBaseScaleY = child.scaleY
            nativeBaseRotation = child.rotation
        }
        when (payload.type) {
            2, 5 -> {
                child.translationX = nativeBaseTranslationX + payload.translationX
                child.translationY = nativeBaseTranslationY + payload.translationY
            }
            3 -> {
                val scale = (nativeBaseScaleX * payload.scale)
                    .coerceIn(nativeMinScale, nativeMaxScale)
                child.scaleX = scale
                child.scaleY = scale
            }
            4 -> child.rotation = nativeBaseRotation + Math.toDegrees(
                payload.rotation.toDouble(),
            ).toFloat()
        }
    }

    private fun resetNativeTransform() {
        val child = getChildAt(0) ?: return
        child.animate().cancel()
        child.translationX = 0f
        child.translationY = 0f
        child.scaleX = 1f
        child.scaleY = 1f
        child.rotation = 0f
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (gestureRequiresParentInterception(
                event.actionMasked,
                gestureRecognizer.requiresMultiPointerStream(),
            )
        ) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        gestureRecognizer.onTouch(event)
        if (gestureRecognitionCancelsPress(
                recognized = gestureRecognizer.hasRecognized(),
                pressActive = gestureActive,
            )
        ) {
            cancelGesture(emitOut = true)
        }
        val handled = super.dispatchTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event, event.actionIndex)
                true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    finishGesture(event, event.actionIndex, cancelled = true)
                }
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) {
                    moveGesture(event, index)
                } else {
                    cancelGesture(emitOut = true)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (finishGesture(event, event.actionIndex, cancelled = false)) {
                    performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishGesture(event, event.actionIndex, cancelled = true)
                true
            }
            else -> true
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (super.onInterceptTouchEvent(event)) return true
        if (!isEnabled || !isClickable) {
            return false
        }
        if (gestureRecognizer.ownsTouchStream()) return true
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return false

        // Images and decorative containers may carry a composed ancestor callback,
        // but the Pressable must own the gesture so its node id is dispatched.
        // Keep genuinely interactive descendants independent.
        return !hasIndependentTouchTargetAt(this, event.x, event.y)
    }

    override fun performClick(): Boolean {
        val platformHandled = super.performClick()
        localOnPress?.invoke()
        onPress?.invoke()
        return platformHandled || localOnPress != null || onPress != null
    }

    override fun performLongClick(): Boolean {
        super.performLongClick()
        val callback = onLongPress ?: return false
        callback()
        return true
    }

    override fun onDetachedFromWindow() {
        gestureRecognizer.cancel()
        cancelGesture(emitOut = false)
        super.onDetachedFromWindow()
    }

    private fun beginGesture(event: MotionEvent, pointerIndex: Int) {
        removeCallbacks(pressOutRunnable)
        cancelGesture(emitOut = false)
        gestureActive = true
        eligibleForPress = true
        activePointerId = event.getPointerId(pointerIndex)
        lastPointer = pointer(event, pointerIndex)
        if (delayPressInMs == 0L) {
            emitPressIn(lastPointer)
        } else {
            postDelayed(pressInRunnable, delayPressInMs)
        }
        postDelayed(longPressRunnable, delayPressInMs + delayLongPressMs)
    }

    private fun hasIndependentTouchTargetAt(
        parent: ViewGroup,
        x: Float,
        y: Float,
    ): Boolean {
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (
                child.visibility != View.VISIBLE ||
                x < child.left + child.translationX ||
                x >= child.right + child.translationX ||
                y < child.top + child.translationY ||
                y >= child.bottom + child.translationY
            ) {
                continue
            }
            val childX = x - child.left - child.translationX + child.scrollX
            val childY = y - child.top - child.translationY + child.scrollY
            if (isIndependentTouchTarget(child)) return true
            if (
                child is ViewGroup &&
                hasIndependentTouchTargetAt(child, childX, childY)
            ) {
                return true
            }
        }
        return false
    }

    private fun isIndependentTouchTarget(view: View): Boolean =
        view is PamPressable ||
            view is Button ||
            view is EditText ||
            view is CompoundButton ||
            view is SeekBar ||
            view is PamScrollContainer ||
            view is PamRecyclerList

    private fun moveGesture(event: MotionEvent, pointerIndex: Int) {
        lastPointer = pointer(event, pointerIndex)
        scheduleMove(lastPointer)
        val wasEligible = eligibleForPress
        eligibleForPress = containsWithRetention(lastPointer.x, lastPointer.y)
        if (wasEligible && !eligibleForPress) {
            removeCallbacks(pressInRunnable)
            removeCallbacks(longPressRunnable)
            emitPressOut()
        } else if (!wasEligible && eligibleForPress) {
            emitPressIn(lastPointer)
            if (!longPressDispatched) {
                postDelayed(longPressRunnable, delayLongPressMs)
            }
        }
    }

    private fun finishGesture(
        event: MotionEvent,
        pointerIndex: Int,
        cancelled: Boolean,
    ): Boolean {
        if (!gestureActive) return false
        if (pointerIndex in 0 until event.pointerCount) {
            lastPointer = pointer(event, pointerIndex)
        }
        val shouldPress = !cancelled &&
            eligibleForPress &&
            containsWithRetention(lastPointer.x, lastPointer.y) &&
            !longPressDispatched
        removeCallbacks(pressInRunnable)
        removeCallbacks(longPressRunnable)
        if (shouldPress) {
            emitPressIn(lastPointer)
        }
        emitPressOut()
        gestureActive = false
        eligibleForPress = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        return shouldPress
    }

    private fun emitPressIn(pointer: PamPressPointer) {
        removeCallbacks(pressOutRunnable)
        if (pressInDispatched) return
        pressInDispatched = true
        isPressed = true
        animate()
            .alpha(pressOpacity)
            .scaleX(targetScaleX * pressScale)
            .scaleY(targetScaleY * pressScale)
            .setDuration(PRESS_IN_ANIMATION_MS)
            .start()
        onPressIn?.invoke(pointer)
    }

    private fun emitPressOut() {
        if (!pressInDispatched) return
        removeCallbacks(pressOutRunnable)
        if (delayPressOutMs == 0L) {
            pressOutRunnable.run()
        } else {
            postDelayed(pressOutRunnable, delayPressOutMs)
        }
    }

    private fun cancelGesture(emitOut: Boolean) {
        removeCallbacks(pressInRunnable)
        removeCallbacks(longPressRunnable)
        if (emitOut) {
            emitPressOut()
        } else {
            removeCallbacks(pressOutRunnable)
            pressInDispatched = false
            isPressed = false
            alpha = targetOpacity
            scaleX = targetScaleX
            scaleY = targetScaleY
        }
        removeCallbacks(moveRunnable)
        moveScheduled = false
        pendingMove = null
        gestureActive = false
        eligibleForPress = false
        longPressDispatched = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun updateClickable() {
        isClickable = localOnPress != null || onPress != null || onPressIn != null ||
            onPressOut != null || onPressMove != null || onLongPress != null ||
            gestureRecognizer.isEnabled()
        if (!isClickable) {
            cancelGesture(emitOut = false)
        }
    }

    private fun scheduleMove(pointer: PamPressPointer) {
        if (onPressMove == null) return
        pendingMove = pointer
        if (moveScheduled) return
        moveScheduled = true
        postOnAnimation(moveRunnable)
    }

    private fun containsWithRetention(x: Float, y: Float): Boolean =
        x >= -retentionLeft &&
            y >= -retentionTop &&
            x < width + retentionRight &&
            y < height + retentionBottom

    private fun pointer(event: MotionEvent, index: Int): PamPressPointer =
        PamPressPointer(
            x = event.getX(index),
            y = event.getY(index),
            pageX = event.rawX,
            pageY = event.rawY,
            timestamp = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
            pointerId = event.getPointerId(index),
        )

    private companion object {
        const val DEFAULT_PRESS_OPACITY = 0.72f
        const val DEFAULT_RETENTION_HORIZONTAL = 20f
        const val DEFAULT_RETENTION_BOTTOM = 30f
        const val MAX_PRESS_DELAY_MS = 60_000L
        const val PRESS_IN_ANIMATION_MS = 70L
        const val PRESS_OUT_ANIMATION_MS = 110L
    }
}

internal fun gestureRequiresParentInterception(
    actionMasked: Int,
    multiPointerGesture: Boolean,
): Boolean = multiPointerGesture && actionMasked == MotionEvent.ACTION_POINTER_DOWN

internal fun gestureRecognitionCancelsPress(
    recognized: Boolean,
    pressActive: Boolean,
): Boolean = recognized && pressActive
