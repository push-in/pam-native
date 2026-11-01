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
    private var pressedBackground: PamPressedBackground? = null

    internal fun configurePressedBackground(shape: android.graphics.drawable.GradientDrawable, base: Int, pressed: Int?) {
        if (pressed == null) {
            pressedBackground?.reset()
            pressedBackground = null
        } else {
            val controller = pressedBackground ?: PamPressedBackground(this).also { pressedBackground = it }
            controller.configure(shape, base, pressed)
        }
    }

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
    /** `accessibilitySelected` of this Pressable (TapEffect `when=unselected`). */
    var tapEffectSelected = false
    private var tapEffectRunners: List<PamMotionRunner> = emptyList()
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
    /** A zoomed native surface owns its pan: ancestors (navigator swipe-back, pagers) may not intercept it. */
    private var zoomedPanOwnsParent = false
    private var nativeMinScale = 1f
    private var nativeMaxScale = 4f
    private var nativeTranslationLimitX = 0f
    private var nativeResetOnEnd = false
    private var nativeFocalZoom = false
    private var nativeBounds = false
    private var nativeContentAspect = 0f
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
        if (onLongPress == null) {
            // React Native: a nested Pressable without onLongPress lets the
            // long press reach the enclosing one (the bubble's gesture), and
            // its own press is cancelled instead of firing on release.
            val ancestor = longPressAncestor()
            if (ancestor != null && ancestor.performNestedLongPress(lastPointer)) {
                // Ends this gesture (no press on release, pressed style out).
                cancelGesture(emitOut = true)
                return@Runnable
            }
        }
        emitPressIn(lastPointer)
        longPressDispatched = performLongClick()
    }
    private val pressOutRunnable = Runnable {
        if (pressInDispatched) {
            pressInDispatched = false
            isPressed = false
            pressedBackground?.setPressed(false)
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
        if (!enabled) {
            pressedBackground?.reset()
            if (hasFocus()) clearFocus()
        }
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
        nativeBounds: Boolean = false,
        nativeContentAspect: Float = 0f,
    ) {
        this.nativeTransformEnabled = nativeTransform
        this.nativeMinScale = nativeMinScale.coerceAtLeast(0.01f)
        this.nativeMaxScale = nativeMaxScale.coerceAtLeast(this.nativeMinScale)
        this.nativeTranslationLimitX = nativeTranslationLimitX.coerceAtLeast(0f)
        this.nativeResetOnEnd = nativeResetOnEnd
        this.nativeFocalZoom = nativeFocalZoom
        this.nativeBounds = nativeBounds
        this.nativeContentAspect = nativeContentAspect.takeIf { it.isFinite() && it > 0f } ?: 0f
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
            if (nativeBounds && payload.type in 2..5) {
                boundedGestures[child] = (boundedGestures[child] ?: 0) + 1
            }
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
                if (nativeBounds) {
                    // The content edge stops at the viewport edge; rebasing makes a
                    // reversed drag move the content at once instead of after the
                    // overshoot is undone.
                    val boundedX = boundedAxis(child, translationTarget.translationX, horizontal = true)
                    val boundedY = boundedAxis(child, translationTarget.translationY, horizontal = false)
                    nativeBaseTranslationX += boundedX - translationTarget.translationX
                    nativeBaseTranslationY += boundedY - translationTarget.translationY
                    translationTarget.translationX = boundedX
                    translationTarget.translationY = boundedY
                }
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
                if (nativeBounds) {
                    child.translationX = boundedAxis(child, child.translationX, horizontal = true)
                    child.translationY = boundedAxis(child, child.translationY, horizontal = false)
                }
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
        if (nativeBounds && payload.type in 2..5 && payload.state in 3..5) {
            val remaining = ((boundedGestures[child] ?: 1) - 1).coerceAtLeast(0)
            if (remaining == 0) boundedGestures.remove(child) else boundedGestures[child] = remaining
            // The last bounded gesture on this surface settles it; a pinch that
            // ends under a still-active pan leaves the release to the pan.
            if (remaining == 0) {
                val settled = settleBounded(child)
                return payload.copy(
                    nativeScale = settled[0],
                    nativeTranslationX = settled[1],
                    nativeTranslationY = settled[2],
                )
            }
        }
        return payload.copy(
            nativeScale = child.scaleX,
            nativeTranslationX = translationTarget.translationX,
            nativeTranslationY = translationTarget.translationY,
        )
    }

    /** [translation] limited so the scaled content never uncovers the viewport on one axis. */
    private fun boundedAxis(child: View, translation: Float, horizontal: Boolean): Float {
        val width = child.width.toFloat()
        val height = child.height.toFloat()
        if (width <= 0f || height <= 0f) return translation
        val (contentWidth, contentHeight) = containedContentSize(width, height, nativeContentAspect)
        return if (horizontal) {
            boundedTranslation(translation, width, contentWidth, child.pivotX, child.scaleX)
        } else {
            boundedTranslation(translation, height, contentHeight, child.pivotY, child.scaleY)
        }
    }

    /**
     * Release of a bounded surface: a scale below 1 (or barely above it)
     * springs back to the centred identity, a larger one keeps its scale and
     * springs its translation inside the content bounds. Returns the resting
     * scale and translation (px) the gesture reports.
     */
    private fun settleBounded(child: View): FloatArray {
        val width = child.width.toFloat()
        val height = child.height.toFloat()
        var scale = child.scaleX.coerceIn(1f, nativeMaxScale.coerceAtLeast(1f))
        if (scale <= BOUNDED_IDENTITY_SNAP) scale = 1f
        val (contentWidth, contentHeight) = containedContentSize(width, height, nativeContentAspect)
        val x = if (width > 0f) {
            boundedTranslation(child.translationX, width, contentWidth, child.pivotX, scale)
        } else {
            0f
        }
        val y = if (height > 0f) {
            boundedTranslation(child.translationY, height, contentHeight, child.pivotY, scale)
        } else {
            0f
        }
        val targets = linkedMapOf(
            PamMotionProperty.SCALE_X to scale.toDouble(),
            PamMotionProperty.SCALE_Y to scale.toDouble(),
            PamMotionProperty.TRANSLATE_X to x.toDouble(),
            PamMotionProperty.TRANSLATE_Y to y.toDouble(),
        )
        val moving = targets.filter { (property, target) ->
            kotlin.math.abs(PamMotionTarget.read(child, property) - target) > 0.01
        }
        if (moving.isNotEmpty()) {
            PamMotionRunner.cancelTransforms(child)
            child.animate().cancel()
            val timeline = PamMotionTimeline.build(
                PamMotionProgram(
                    0L,
                    1,
                    listOf(
                        moving.mapValues { (_, target) ->
                            listOf(PamMotionStep.Spring(PamMotionValue(target), BOUNDED_SPRING, 0.0, 0L))
                        },
                    ),
                ),
                current = { PamMotionTarget.read(child, it) },
                resolve = { _, value -> value.number },
            )
            PamMotionRunner(child, timeline, iterations = 1)
                .start(reducedMotion = PamMotionPolicy.isReduced(context))
        }
        return floatArrayOf(scale, x, y)
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
        claimZoomedPan(event)
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
            if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) zoomedPanOwnsParent = false
        }
        return handled
    }

    /**
     * While the native transform shows its content zoomed in, a drag pans
     * the content for the whole touch: the parent chain (a navigator's
     * swipe-back, a pager) may not intercept it, even at the content edge,
     * so a zoomed photo never leaves the screen. At scale 1 nothing changes.
     */
    private fun claimZoomedPan(event: MotionEvent) {
        if (!nativeTransformEnabled || drag.config != null) return
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val child = nativeTransformTarget()
        zoomedPanOwnsParent = child != null && zoomedContentOwnsPan(child.scaleX)
        if (zoomedPanOwnsParent) parent?.requestDisallowInterceptTouchEvent(true)
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
        playTapEffect(pointer, PamTapEffect.TRIGGER_TAP)
        // A shared element inside this press starts flying on this frame,
        // before the press reaches the app (SharedTransitionStyle::launchOnTap).
        PamNavigationHost.pressed(this)
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
            playTapEffect(pointer, PamTapEffect.TRIGGER_DOUBLE_TAP)
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

    private fun playTapEffect(pointer: PamPressPointer, gesture: Int) {
        val effect = tapEffect?.takeIf { it.playsOn(gesture, tapEffectSelected) } ?: return
        val anchor = PamDragController.findRef(this, effect.ref) ?: return
        // In place, the anchor itself pops; otherwise it is moved and its first child plays.
        val animated = if (effect.origin == PamTapEffect.ORIGIN_ANCHOR) {
            anchor
        } else {
            (anchor as? ViewGroup)?.getChildAt(0) ?: anchor
        }
        if (effect.origin != PamTapEffect.ORIGIN_ANCHOR) {
            var offsetX = 0f
            var offsetY = 0f
            var parentView = anchor.parent as? View
            while (parentView != null && parentView !== this) {
                offsetX += parentView.left + parentView.translationX
                offsetY += parentView.top + parentView.translationY
                parentView = parentView.parent as? View
            }
            val x = if (effect.origin == PamTapEffect.ORIGIN_CENTER) width / 2f else pointer.x
            val y = if (effect.origin == PamTapEffect.ORIGIN_CENTER) height / 2f else pointer.y
            anchor.translationX = x - offsetX - anchor.left - anchor.width / 2f
            anchor.translationY = y - offsetY - anchor.top - anchor.height / 2f
        }
        if (effect.origin != PamTapEffect.ORIGIN_ANCHOR || effect.tiltDegrees > 0f) {
            anchor.rotation = if (effect.tiltDegrees > 0f) {
                ((Math.random() - 0.5) * 2.0 * effect.tiltDegrees).toFloat()
            } else {
                0f
            }
        }
        tapEffectRunners.forEach(PamMotionRunner::cancel)
        // Each layer program plays on the anchor's next child (a burst's ring beside its heart).
        val layers = if (effect.origin == PamTapEffect.ORIGIN_ANCHOR) {
            emptyList()
        } else {
            effect.layers.mapIndexedNotNull { index, program ->
                (anchor as? ViewGroup)?.getChildAt(index + 1)?.let { it to program }
            }
        }
        val reduced = PamMotionPolicy.isReduced(context)
        tapEffectRunners = (listOf(animated to effect.program) + layers).map { (view, program) ->
            val timeline = PamMotionTimeline.build(
                program,
                current = { PamMotionTarget.read(view, it) },
                resolve = { property, value -> PamMotionTarget.resolve(view, property, value) },
            )
            PamMotionRunner(view, timeline, program.iterations).also { it.start(reduced) }
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) pressedBackground?.reset()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) pressedBackground?.reset()
    }

    override fun onDetachedFromWindow() {
        gestureRecognizer.cancel()
        cancelGesture(emitOut = false)
        removeCallbacks(singleTapRunnable)
        pendingSingleTap = null
        tapEffectRunners.forEach(PamMotionRunner::cancel)
        tapEffectRunners = emptyList()
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
        postDelayed(longPressRunnable, delayPressInMs + effectiveLongPressDelayMs())
    }

    /** Own delay, or the ancestor's when this Pressable lends it the long press. */
    private fun effectiveLongPressDelayMs(): Long =
        nestedLongPressDelay(
            own = delayLongPressMs,
            hasOwnLongPress = onLongPress != null,
            ancestorDelay = if (onLongPress == null) longPressAncestor()?.delayLongPressMs else null,
        )

    /**
     * The nearest enclosing Pressable that handles long presses, without
     * crossing a scroll container or list (the same boundary RN responders
     * stop at for a row inside a list).
     */
    private fun longPressAncestor(): PamPressable? {
        val chain = ArrayList<NestedLongPressNode>()
        val views = ArrayList<PamPressable?>()
        var ancestor = parent
        while (ancestor is View) {
            when (ancestor) {
                is PamScrollContainer, is PamRecyclerList -> {
                    chain += NestedLongPressNode(hasLongPress = false, enabled = false, boundary = true)
                    views += null
                }
                is PamPressable -> {
                    chain += NestedLongPressNode(
                        hasLongPress = ancestor.onLongPress != null,
                        enabled = ancestor.isEnabled,
                        boundary = false,
                    )
                    views += ancestor
                }
            }
            if (chain.lastOrNull()?.boundary == true) break
            ancestor = ancestor.parent
        }
        val index = nestedLongPressTarget(chain)
        return if (index >= 0) views[index] else null
    }

    /** Long press lent by a nested Pressable: [pointer] is in that child's space. */
    internal fun performNestedLongPress(pointer: PamPressPointer): Boolean {
        val callback = onLongPress ?: return false
        if (!isEnabled) return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        val local = pointer.copy(x = pointer.pageX - location[0], y = pointer.pageY - location[1])
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        callback(local)
        return true
    }

    private fun hasIndependentTouchTargetAt(
        parent: ViewGroup,
        x: Float,
        y: Float,
    ): Boolean {
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            // The full child transform (scale, rotation, pivot), as ViewGroup
            // hit testing does: a zoomed photo's tap target is its visual box.
            val local = childLocalPoint(child, x, y) ?: continue
            if (local[0] < 0f || local[0] >= child.width || local[1] < 0f || local[1] >= child.height) {
                continue
            }
            val childX = local[0] + child.scrollX
            val childY = local[1] + child.scrollY
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
                postDelayed(longPressRunnable, effectiveLongPressDelayMs())
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
        if (showsPressFeedback(hasDoubleTap = onDoubleTap != null)) {
            pressedBackground?.setPressed(true)
            animate()
                .alpha(pressOpacity)
                .scaleX(targetScaleX * pressScale)
                .scaleY(targetScaleY * pressScale)
                .setDuration(PRESS_IN_ANIMATION_MS)
                .start()
        }
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
            pressedBackground?.reset()
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
        /** Bounded native gestures in progress per surface (adjacent pan + pinch share one). */
        val boundedGestures = java.util.WeakHashMap<View, Int>()
        /** RN MediaViewer OPEN_SPRING (stiffness 240, damping 24, mass 0.9). */
        val BOUNDED_SPRING = PamSpringConfig(stiffness = 240.0, damping = 24.0, mass = 0.9)
        const val BOUNDED_IDENTITY_SNAP = 1.02f
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

/**
 * `ref=<nativeRef>;tilt=<deg>[;origin=touch|center|anchor][;trigger=double|tap][;armed=0][;when=unselected]`
 * line followed by a `pam-motion` program and, optionally, more programs
 * ([layers]): with a moved origin, layer `i` plays on the anchor's child `i + 1`.
 */
internal data class PamTapEffect(
    val ref: String,
    val tiltDegrees: Float,
    val program: PamMotionProgram,
    val origin: Int = ORIGIN_TOUCH,
    val trigger: Int = TRIGGER_DOUBLE_TAP,
    val armed: Boolean = true,
    val unlessSelected: Boolean = false,
    val layers: List<PamMotionProgram> = emptyList(),
) {
    /** Whether [gesture] (a `TRIGGER_*`) plays this effect on a Pressable [selected] or not. */
    fun playsOn(gesture: Int, selected: Boolean = false): Boolean =
        armed && trigger == gesture && !(unlessSelected && selected)

    companion object {
        const val ORIGIN_TOUCH = 1
        const val ORIGIN_CENTER = 2
        const val ORIGIN_ANCHOR = 3
        const val TRIGGER_DOUBLE_TAP = 1
        const val TRIGGER_TAP = 2

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
            val programs = splitPrograms(source.substring(newline + 1))
                .map { PamMotionProgram.parse(it) ?: return null }
            val program = programs.firstOrNull() ?: return null
            return PamTapEffect(
                ref = ref,
                tiltDegrees = (options["tilt"]?.toFloatOrNull() ?: 0f).coerceIn(0f, 180f),
                program = program,
                origin = when (options["origin"]) {
                    "center" -> ORIGIN_CENTER
                    "anchor" -> ORIGIN_ANCHOR
                    else -> ORIGIN_TOUCH
                },
                trigger = if (options["trigger"] == "tap") TRIGGER_TAP else TRIGGER_DOUBLE_TAP,
                armed = options["armed"] != "0",
                unlessSelected = options["when"] == "unselected",
                layers = programs.drop(1),
            )
        }

        /** Programs of a tap effect body, each starting at its `pam-motion` header line. */
        internal fun splitPrograms(body: String): List<String> {
            val programs = mutableListOf<StringBuilder>()
            for (line in body.lineSequence()) {
                if (programs.isEmpty() && line.isBlank()) continue
                if (line.trimStart().startsWith("pam-motion ") || programs.isEmpty()) programs += StringBuilder()
                programs.last().append(line).append('\n')
            }
            return programs.map(StringBuilder::toString)
        }
    }
}

/** One ancestor seen by a nested Pressable looking for a long-press handler. */
internal data class NestedLongPressNode(
    val hasLongPress: Boolean,
    val enabled: Boolean,
    val boundary: Boolean,
)

/**
 * Index in [chain] (nearest ancestor first) of the Pressable that receives a
 * long press its nested child does not handle, or -1. A scroll/list boundary
 * stops the search.
 */
internal fun nestedLongPressTarget(chain: List<NestedLongPressNode>): Int {
    for ((index, node) in chain.withIndex()) {
        if (node.boundary) return -1
        if (node.hasLongPress) return if (node.enabled) index else -1
    }
    return -1
}

/** The child's own delay when it handles long presses, else the receiver's. */
internal fun nestedLongPressDelay(own: Long, hasOwnLongPress: Boolean, ancestorDelay: Long?): Long =
    if (hasOwnLongPress || ancestorDelay == null) own else ancestorDelay

internal fun gestureRequiresParentInterception(
    actionMasked: Int,
    multiPointerGesture: Boolean,
): Boolean = multiPointerGesture && actionMasked == MotionEvent.ACTION_POINTER_DOWN

/** A native transform zoomed past the identity snap owns the drag over it. */
internal fun zoomedContentOwnsPan(scale: Float): Boolean = scale > ZOOMED_PAN_MIN_SCALE

/** Same as the bounded identity snap: below it a release springs back to 1x. */
internal const val ZOOMED_PAN_MIN_SCALE = 1.02f

internal fun gestureRecognitionCancelsPress(
    recognized: Boolean,
    pressActive: Boolean,
): Boolean = recognized && pressActive

/**
 * Translation that keeps the content point under the pinch focus in place:
 * the point under [focalStart] (relative to the untranslated [pivot]) is
 * scaled by [ratio] and lands under the current [focal].
 */
/**
 * [x], [y] in the parent's coordinates mapped into [child]'s own coordinates
 * through its full transform; null when the transform is not invertible.
 */
internal fun childLocalPoint(child: View, x: Float, y: Float): FloatArray? {
    val point = floatArrayOf(x - child.left, y - child.top)
    val matrix = child.matrix
    if (!matrix.isIdentity) {
        val inverse = android.graphics.Matrix()
        if (!matrix.invert(inverse)) return null
        inverse.mapPoints(point)
    }
    return point
}

internal fun containedContentSize(width: Float, height: Float, aspect: Float): Pair<Float, Float> {
    if (aspect <= 0f || width <= 0f || height <= 0f) return width to height
    val fittedWidth = kotlin.math.min(width, height * aspect)
    return fittedWidth to fittedWidth / aspect
}

/**
 * One axis of a bounded native transform. The content ([content] long,
 * centred in a [viewport]-long view scaled by [scale] around [pivot]) may
 * move only while it still covers the viewport; content shorter than the
 * viewport stays centred.
 */
internal fun boundedTranslation(
    translation: Float,
    viewport: Float,
    content: Float,
    pivot: Float,
    scale: Float,
): Float {
    val contentStart = (viewport - content) / 2f
    val start = pivot - (pivot - contentStart) * scale
    val end = pivot + (contentStart + content - pivot) * scale
    if (end - start <= viewport) return viewport / 2f - (start + end) / 2f
    return translation.coerceIn(viewport - end, -start)
}

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

/**
 * A surface that also takes a double tap (like to heart, tap to zoom) shows
 * no press dimming: each tap of the double tap would flash it, and RN's
 * Pressable has no default feedback there.
 */
internal fun showsPressFeedback(hasDoubleTap: Boolean): Boolean = !hasDoubleTap
