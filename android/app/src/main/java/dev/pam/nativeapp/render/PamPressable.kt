package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
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
    private var onPress: ((PamPressPointer) -> Unit)? = null
    private var localOnPress: (() -> Unit)? = null
    private var onLongPress: ((PamPressPointer) -> Unit)? = null
    private var onDoubleTap: ((PamPressPointer) -> Unit)? = null
    private var doubleTapDelayMs = DEFAULT_DOUBLE_TAP_DELAY_MS
    private var pendingSingleTap: PamPressPointer? = null
    private var clickPointer: PamPressPointer? = null
    private var lastTap: PamPressPointer? = null
    private var tapEffect: PamTapEffect? = null
    private var tapEffectRunner: PamMotionRunner? = null
    internal val drag = PamDragController(this)
    private var onPressIn: ((PamPressPointer) -> Unit)? = null
    private var onPressOut: ((PamPressPointer) -> Unit)? = null
    private var onPressMove: ((PamPressPointer) -> Unit)? = null
    /** Notified when the visual pressed state changes (CSS :pressed/:active styles). */
    var onPressedStateChanged: ((Boolean) -> Unit)? = null
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
    private var nativeTranslationLimitX = 0f
    private var nativeResetOnEnd = false
    private var nativeFocalZoom = false
    private var nativeFocalStartX = 0f
    private var nativeFocalStartY = 0f
    private var nativeResetKey = 0L
    private var nativeBaseTranslationX = 0f
    private var nativeBaseTranslationY = 0f
    private var nativeBaseScaleX = 1f
    private var nativeBaseScaleY = 1f
    private var nativeBaseRotation = 0f
    private var nativeGestureTransformActive = false
    private var nativeAppliedTranslationX = 0f
    private var nativeAppliedTranslationY = 0f
    private var nativeTransformTarget: View? = null
    private var nativeInteractionEnabled = false

    fun setNativeTransformTarget(target: View?) {
        nativeTransformTarget = target
    }

    fun setNativeInteractionEnabled(enabled: Boolean) {
        nativeInteractionEnabled = enabled
        updateClickable()
    }

    private fun nativeTransformTarget(): View? {
        val child = nativeTransformTarget?.takeIf { it.parent === this } ?: getChildAt(0)
        // Adjacent native detectors compose on one content surface. An explicit
        // View between them preserves independent transform targets.
        return if (nativeTransformEnabled && drag.config == null &&
            child is PamPressable && child.nativeTransformEnabled && child.drag.config == null
        ) child.nativeTransformTarget() ?: child else child
    }

    private val singleTapRunnable = Runnable {
        val pointer = pendingSingleTap ?: return@Runnable
        pendingSingleTap = null
        performClickAt(pointer)
    }

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
            onPressedStateChanged?.invoke(false)
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
        onPress: ((PamPressPointer) -> Unit)?,
        onLongPress: ((PamPressPointer) -> Unit)?,
        onPressIn: ((PamPressPointer) -> Unit)?,
        onPressOut: ((PamPressPointer) -> Unit)?,
        onPressMove: ((PamPressPointer) -> Unit)?,
        onDoubleTap: ((PamPressPointer) -> Unit)? = null,
    ) {
        this.onPress = onPress
        this.onLongPress = onLongPress
        this.onDoubleTap = onDoubleTap
        if (onDoubleTap == null) {
            removeCallbacks(singleTapRunnable)
            pendingSingleTap = null
        }
        this.onPressIn = onPressIn
        this.onPressOut = onPressOut
        this.onPressMove = onPressMove
        isLongClickable = onLongPress != null
        updateClickable()
    }

    fun configureDoubleTap(delayMs: Long, effect: PamTapEffect?) {
        doubleTapDelayMs = delayMs.coerceIn(MIN_DOUBLE_TAP_DELAY_MS, MAX_DOUBLE_TAP_DELAY_MS)
        tapEffect = effect
    }

    fun configureDrag(config: PamDragConfig?, onSettle: ((Int, Double) -> Unit)?) {
        drag.configure(config, onSettle)
    }

    val hasLocalOnPress: Boolean
        get() = localOnPress != null

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
        nativeTranslationLimitX: Float = 0f,
        nativeResetOnEnd: Boolean = false,
        nativeFocalZoom: Boolean = false,
    ) {
        this.nativeTransformEnabled = nativeTransform
        this.nativeMinScale = nativeMinScale.coerceAtLeast(0.01f)
        this.nativeMaxScale = nativeMaxScale.coerceAtLeast(this.nativeMinScale)
        this.nativeTranslationLimitX = nativeTranslationLimitX.coerceAtLeast(0f)
        this.nativeResetOnEnd = nativeResetOnEnd
        this.nativeFocalZoom = nativeFocalZoom
        if (this.nativeResetKey != nativeResetKey) {
            this.nativeResetKey = nativeResetKey
            resetNativeTransform()
        }
        gestureRecognizer.configure(config) { payload ->
            val delivered = applyNativeTransform(applyDrag(payload))
            callback?.invoke(delivered)
            drag.flushReleaseSettle()
        }
        updateClickable()
    }

    private fun applyDrag(payload: PamGesturePayload): PamGesturePayload {
        if (drag.config == null || payload.type != GESTURE_PAN) return payload
        when (payload.state) {
            1 -> {
                drag.begin(payload.x, payload.y, payload.translationX, payload.translationY)
                drag.update(payload.translationX, payload.translationY)
            }
            2 -> drag.update(payload.translationX, payload.translationY)
            3, 4 -> {
                if (payload.state == 3) drag.update(payload.translationX, payload.translationY)
                val release = drag.end(payload.velocityX, payload.velocityY, payload.state == 4)
                    ?: return payload
                return payload.copy(
                    snapIndex = release.snapIndex,
                    thresholdReached = release.thresholdReached,
                )
            }
        }
        return payload
    }

    private fun applyNativeTransform(payload: PamGesturePayload): PamGesturePayload {
        if (!nativeTransformEnabled || drag.config != null) return payload
        traceGesture("transform type=${payload.type} state=${payload.state} x=${payload.translationX}")
        val child = nativeTransformTarget() ?: return payload
        val translationTarget = child
        if (payload.state == 1) {
            // Direct manipulation owns the currently displayed transform,
            // including a photo zoom interrupted before its spring settled.
            PamMotionRunner.cancelTransforms(child)
            child.animate().cancel()
            nativeBaseTranslationX = translationTarget.translationX
            nativeBaseTranslationY = translationTarget.translationY
            nativeBaseScaleX = child.scaleX
            nativeBaseScaleY = child.scaleY
            nativeBaseRotation = child.rotation
            if (payload.type == 3 && nativeFocalZoom) {
                nativeFocalStartX = payload.focalX
                nativeFocalStartY = payload.focalY
                focalZoomTargets[child] = true
            }
        }
        when (payload.type) {
            2, 5 -> if (focalZoomTargets[translationTarget] == true) {
                // A focal pinch owns the translation of this surface: follow it
                // and resume panning from wherever the pinch leaves the content.
                nativeBaseTranslationX = translationTarget.translationX - payload.translationX
                nativeBaseTranslationY = translationTarget.translationY - payload.translationY
                nativeGestureTransformActive = false
            } else {
                // The finger moves in screen pixels; the target translates in its
                // parent's space, which a scaled or rotated ancestor transforms.
                val (deltaX, deltaY) = parentSpaceVector(translationTarget, payload.translationX, payload.translationY)
                val translatedX = nativeBaseTranslationX + deltaX
                translationTarget.translationX = if (nativeTranslationLimitX > 0f) {
                    translatedX.coerceIn(-nativeTranslationLimitX, nativeTranslationLimitX)
                } else {
                    translatedX
                }
                translationTarget.translationY = nativeBaseTranslationY + deltaY
                nativeGestureTransformActive = payload.state in 1..2
                nativeAppliedTranslationX = translationTarget.translationX
                nativeAppliedTranslationY = translationTarget.translationY
                traceGesture(
                    "applied targetX=${translationTarget.translationX} " +
                        "childX=${child.translationX}",
                )
            }
            3 -> {
                val scale = (nativeBaseScaleX * payload.scale)
                    .coerceIn(nativeMinScale, nativeMaxScale)
                if (nativeFocalZoom) {
                    // Keep the content point that was under the fingers' centroid
                    // under it: zoom around the focus and follow its movement.
                    val (pivotX, pivotY) = pivotIn(child)
                    val ratio = scale / nativeBaseScaleX.coerceAtLeast(0.0001f)
                    child.translationX = focalZoomTranslation(
                        payload.focalX, nativeFocalStartX, pivotX, nativeBaseTranslationX, ratio,
                    )
                    child.translationY = focalZoomTranslation(
                        payload.focalY, nativeFocalStartY, pivotY, nativeBaseTranslationY, ratio,
                    )
                    if (payload.state in 3..5) focalZoomTargets.remove(child)
                }
                child.scaleX = scale
                child.scaleY = scale
            }
            4 -> child.rotation = nativeBaseRotation + Math.toDegrees(
                payload.rotation.toDouble(),
            ).toFloat()
        }
        if (nativeResetOnEnd && payload.state in 3..5) {
            nativeGestureTransformActive = false
            translationTarget.animate().cancel()
            translationTarget.animate()
                .translationX(nativeBaseTranslationX)
                .translationY(nativeBaseTranslationY)
                .setDuration(180L)
                .start()
        }
        return payload.copy(
            nativeScale = child.scaleX,
            nativeTranslationX = translationTarget.translationX,
            nativeTranslationY = translationTarget.translationY,
        )
    }

    /**
     * A screen-space vector expressed in [target]'s parent coordinates: the
     * inverse of every ancestor transform (scale, rotation) above it. With no
     * transformed ancestor it is returned unchanged.
     */
    private fun parentSpaceVector(target: View, x: Float, y: Float): Pair<Float, Float> {
        val vector = floatArrayOf(x, y)
        return if (mapToParentSpace(target, vector)) vector[0] to vector[1] else x to y
    }

    /** The child's untranslated pivot in this view's coordinates. */
    private fun pivotIn(child: View): Pair<Float, Float> {
        var x = child.pivotX
        var y = child.pivotY
        x += child.left
        y += child.top
        var ancestor = child.parent as? View
        while (ancestor != null && ancestor !== this) {
            x += ancestor.left + ancestor.translationX
            y += ancestor.top + ancestor.translationY
            ancestor = ancestor.parent as? View
        }
        return x to y
    }

    private fun resetNativeTransform() {
        val child = nativeTransformTarget() ?: return
        val translationTarget = child
        translationTarget.animate().cancel()
        translationTarget.translationX = 0f
        translationTarget.translationY = 0f
        child.animate().cancel()
        child.scaleX = 1f
        child.scaleY = 1f
        child.rotation = 0f
        nativeGestureTransformActive = false
        nativeAppliedTranslationX = 0f
        nativeAppliedTranslationY = 0f
    }

    override fun dispatchDraw(canvas: Canvas) {
        val child = nativeTransformTarget()
        if (nativeGestureTransformActive && child != null) {
            val checkpoint = canvas.save()
            val originalX = child.translationX
            val originalY = child.translationY
            try {
                child.translationX = 0f
                child.translationY = 0f
                canvas.translate(nativeAppliedTranslationX, nativeAppliedTranslationY)
                super.dispatchDraw(canvas)
            } finally {
                child.translationX = originalX
                child.translationY = originalY
                canvas.restoreToCount(checkpoint)
            }
            return
        }
        super.dispatchDraw(canvas)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        traceGesture(
            "touch action=${event.actionMasked} x=${event.x} y=${event.y} " +
                "rawX=${event.rawX} rawY=${event.rawY}",
        )
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

    private fun traceGesture(message: String) {
        if (dev.pam.nativeapp.BuildConfig.DEBUG && Log.isLoggable(GESTURE_LOG_TAG, Log.DEBUG)) {
            Log.d(GESTURE_LOG_TAG, "${transitionName ?: id}: $message")
        }
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
                    if (onDoubleTap == null) {
                        clickPointer = lastPointer
                        performClick()
                    } else {
                        handleTap(lastPointer)
                    }
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
        // Touch clicks carry the release point; accessibility clicks the centre.
        val pointer = clickPointer ?: centerPointer()
        clickPointer = null
        localOnPress?.invoke()
        onPress?.invoke(pointer)
        return platformHandled || localOnPress != null || onPress != null
    }

    private fun performClickAt(pointer: PamPressPointer): Boolean {
        clickPointer = pointer
        return performClick()
    }

    override fun performLongClick(): Boolean {
        val platformHandled = super.performLongClick()
        val pointer = if (gestureActive) lastPointer else centerPointer()
        onLongPress?.invoke(pointer)
        return platformHandled || onLongPress != null
    }

    /**
     * RN double-tap pattern on the UI thread: with a double-tap handler the
     * single press waits [doubleTapDelayMs] and is cancelled by a second tap
     * near the first one.
     */
    private fun handleTap(pointer: PamPressPointer) {
        val doubleTap = onDoubleTap ?: return
        val previous = lastTap
        val slop = ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
        if (
            previous != null &&
            pointer.timestamp - previous.timestamp <= doubleTapDelayMs &&
            kotlin.math.hypot(pointer.x - previous.x, pointer.y - previous.y) <= slop
        ) {
            removeCallbacks(singleTapRunnable)
            pendingSingleTap = null
            lastTap = null
            playTapEffect(pointer)
            doubleTap(pointer)
            return
        }
        lastTap = pointer
        if (onPress != null || localOnPress != null) {
            pendingSingleTap = pointer
            removeCallbacks(singleTapRunnable)
            postDelayed(singleTapRunnable, doubleTapDelayMs)
        }
    }

    private fun playTapEffect(pointer: PamPressPointer) {
        val effect = tapEffect ?: return
        val anchor = PamDragController.findRef(this, effect.ref) ?: return
        val animated = (anchor as? ViewGroup)?.getChildAt(0) ?: anchor
        var offsetX = 0f
        var offsetY = 0f
        var parentView = anchor.parent as? View
        while (parentView != null && parentView !== this) {
            offsetX += parentView.left + parentView.translationX
            offsetY += parentView.top + parentView.translationY
            parentView = parentView.parent as? View
        }
        anchor.translationX = pointer.x - offsetX - anchor.left - anchor.width / 2f
        anchor.translationY = pointer.y - offsetY - anchor.top - anchor.height / 2f
        anchor.rotation = if (effect.tiltDegrees > 0f) {
            ((Math.random() - 0.5) * 2.0 * effect.tiltDegrees).toFloat()
        } else {
            0f
        }
        tapEffectRunner?.cancel()
        val timeline = PamMotionTimeline.build(
            effect.program,
            current = { PamMotionTarget.read(animated, it) },
            resolve = { property, value -> PamMotionTarget.resolve(animated, property, value) },
        )
        tapEffectRunner = PamMotionRunner(animated, timeline, effect.program.iterations).also {
            it.start(PamMotionPolicy.isReduced(context))
        }
    }

    private fun centerPointer(): PamPressPointer {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return PamPressPointer(
            x = width / 2f,
            y = height / 2f,
            pageX = location[0] + width / 2f,
            pageY = location[1] + height / 2f,
            timestamp = SystemClock.uptimeMillis(),
            pointerId = 0,
        )
    }

    override fun onDetachedFromWindow() {
        gestureRecognizer.cancel()
        cancelGesture(emitOut = false)
        removeCallbacks(singleTapRunnable)
        pendingSingleTap = null
        tapEffectRunner?.cancel()
        tapEffectRunner = null
        drag.detach()
        nativeTransformTarget = null
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
        onPressedStateChanged?.invoke(true)
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
            onDoubleTap != null ||
            onPressOut != null || onPressMove != null || onLongPress != null ||
            gestureRecognizer.isEnabled() || nativeInteractionEnabled
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
        /** Surfaces whose translation a focal pinch currently owns (shared by adjacent detectors). */
        val focalZoomTargets = java.util.WeakHashMap<View, Boolean>()
        const val GESTURE_LOG_TAG = "PamGesture"
        const val DEFAULT_PRESS_OPACITY = 0.72f
        const val DEFAULT_RETENTION_HORIZONTAL = 20f
        const val DEFAULT_RETENTION_BOTTOM = 30f
        const val MAX_PRESS_DELAY_MS = 60_000L
        const val PRESS_IN_ANIMATION_MS = 70L
        const val PRESS_OUT_ANIMATION_MS = 110L
        const val GESTURE_PAN = 2
        const val DEFAULT_DOUBLE_TAP_DELAY_MS = 250L
        const val MIN_DOUBLE_TAP_DELAY_MS = 80L
        const val MAX_DOUBLE_TAP_DELAY_MS = 1_000L
    }
}

/** `ref=<nativeRef>;tilt=<deg>` line followed by a `pam-motion` program. */
internal data class PamTapEffect(
    val ref: String,
    val tiltDegrees: Float,
    val program: PamMotionProgram,
) {
    companion object {
        private val parsed = PamParseCache<PamTapEffect>()

        /** [parse] memoized by source (one tap effect shared by every list row). */
        fun cached(source: String): PamTapEffect? = parsed.getOrParse(source, ::parse)

        fun parse(source: String): PamTapEffect? {
            val newline = source.indexOf('\n')
            if (newline < 0) return null
            val options = source.substring(0, newline).split(';', ' ').mapNotNull {
                val parts = it.trim().split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val ref = options["ref"]?.takeIf(String::isNotEmpty) ?: return null
            val program = PamMotionProgram.parse(source.substring(newline + 1)) ?: return null
            return PamTapEffect(
                ref = ref,
                tiltDegrees = (options["tilt"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 180f),
                program = program,
            )
        }
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

/**
 * Translation that keeps the content point under the pinch focus in place:
 * the point under [focalStart] (relative to the untranslated [pivot]) is
 * scaled by [ratio] and lands under the current [focal].
 */
internal fun focalZoomTranslation(
    focal: Float,
    focalStart: Float,
    pivot: Float,
    baseTranslation: Float,
    ratio: Float,
): Float = (focal - pivot) - (focalStart - pivot - baseTranslation) * ratio

/**
 * Maps a screen-space [vector] (in place) into the coordinate space of
 * [target]'s parent by inverting the transforms of every ancestor, from the
 * parent up to the root. Returns false (vector untouched) when no ancestor
 * is transformed or the combined transform is not invertible.
 */
internal fun mapToParentSpace(target: View, vector: FloatArray): Boolean {
    val combined = android.graphics.Matrix()
    var ancestor = target.parent as? View
    var transformed = false
    while (ancestor != null) {
        val matrix = ancestor.matrix
        if (!matrix.isIdentity) {
            combined.postConcat(matrix)
            transformed = true
        }
        ancestor = ancestor.parent as? View
    }
    if (!transformed) return false
    val inverse = android.graphics.Matrix()
    if (!combined.invert(inverse)) return false
    inverse.mapVectors(vector)
    return true
}
