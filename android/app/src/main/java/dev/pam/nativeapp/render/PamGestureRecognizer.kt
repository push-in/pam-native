package dev.pam.nativeapp.render

import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal data class PamGestureConfig(
    val type: Int,
    val enabled: Boolean,
    val minPointers: Int,
    val maxPointers: Int,
    val direction: Int,
    val composition: Int,
    val minDistance: Float,
    val minDurationMs: Long,
)

internal data class PamGesturePayload(
    val type: Int,
    val state: Int,
    val x: Float,
    val y: Float,
    val pageX: Float,
    val pageY: Float,
    val translationX: Float,
    val translationY: Float,
    val velocityX: Float,
    val velocityY: Float,
    val scale: Float,
    val rotation: Float,
    val pointerCount: Int,
    val timestamp: Long,
    val snapIndex: Int = -1,
    val thresholdReached: Boolean = false,
    val nativeScale: Float? = null,
    val nativeTranslationX: Float? = null,
    val nativeTranslationY: Float? = null,
    /** Centroid of the first two pointers (the pinch focus), in this view's coordinates. */
    val focalX: Float = x,
    val focalY: Float = y,
)

internal class PamGestureRecognizer(private val view: View) {
    private var config: PamGestureConfig? = null
    private var emit: ((PamGesturePayload) -> Unit)? = null
    private var startX = 0f
    private var startY = 0f
    private var startPageX = 0f
    private var startPageY = 0f
    private var startDistance = 0f
    private var startAngle = 0f
    private var beganAt = 0L
    private var active = false
    private var recognized = false
    private var velocity: VelocityTracker? = null
    private var pendingUpdate: PamGesturePayload? = null
    private var updateScheduled = false
    private var panOwnsParent = false
    private var tentativeParentClaim = false
    /** Screen-to-detector map of the event being handled ([PamGestureSpace]). */
    private val localSpace = FloatArray(4)
    private var localSpaceTransformed = false
    private val matrixValues = FloatArray(9)
    private val displacement = FloatArray(2)
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()

    // A held thumbnail must retain the first MOVE before a horizontal scroll
    // ancestor intercepts it. Holding alone emits no PHP gesture event.
    private val holdPan = Runnable {
        val current = config ?: return@Runnable
        if (current.enabled && current.type == TYPE_PAN && active && !recognized) {
            claimParent()
        }
    }

    private val longPress = Runnable {
        val current = config ?: return@Runnable
        if (
            current.enabled &&
            current.type == TYPE_LONG_PRESS &&
            active &&
            !recognized
        ) {
            recognized = true
            emitNow(payload(current, STATE_BEGAN, lastEvent = null))
        }
    }

    private val update = Runnable {
        updateScheduled = false
        pendingUpdate?.let(::emitNow)
        pendingUpdate = null
    }

    fun configure(
        next: PamGestureConfig?,
        callback: ((PamGesturePayload) -> Unit)?,
    ) {
        if (next != config || callback == null) cancel(emitCancel = active && recognized)
        config = next
        emit = callback
    }

    fun isEnabled(): Boolean = config?.enabled == true && emit != null

    fun hasRecognized(): Boolean = recognized

    fun requiresMultiPointerStream(): Boolean =
        isEnabled() && (config?.type == TYPE_PINCH || config?.type == TYPE_ROTATION)

    fun ownsTouchStream(): Boolean =
        isEnabled() && (
            config?.composition == COMPOSITION_EXCLUSIVE ||
                config?.composition == COMPOSITION_RACE && recognized
            )

    fun onTouch(event: MotionEvent) {
        val current = config ?: return
        if (!current.enabled || emit == null) return
        if (active && event.actionMasked != MotionEvent.ACTION_DOWN) refreshLocalSpace()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event, current)
            MotionEvent.ACTION_POINTER_DOWN -> {
                velocity?.addMovement(event)
                if (event.pointerCount > current.maxPointers) {
                    cancel(emitCancel = recognized, event)
                } else if (current.type == TYPE_PINCH || current.type == TYPE_ROTATION) {
                    if (event.pointerCount >= current.minPointers) {
                        captureMultiPointer(event)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> move(event, current)
            MotionEvent.ACTION_POINTER_UP -> {
                velocity?.addMovement(event)
                if (
                    recognized &&
                    (current.type == TYPE_PINCH || current.type == TYPE_ROTATION) &&
                    event.pointerCount - 1 < current.minPointers
                ) {
                    finish(event, current, cancelled = false)
                }
            }
            MotionEvent.ACTION_UP -> finish(event, current, cancelled = false)
            MotionEvent.ACTION_CANCEL -> finish(event, current, cancelled = true)
        }
    }

    fun cancel(emitCancel: Boolean = false, event: MotionEvent? = null) {
        view.removeCallbacks(holdPan)
        releaseParent()
        view.removeCallbacks(longPress)
        view.removeCallbacks(update)
        if (emitCancel && recognized) {
            config?.let {
                emitNow(payload(it, STATE_CANCELLED, event))
            }
        }
        velocity?.recycle()
        velocity = null
        pendingUpdate = null
        updateScheduled = false
        active = false
        recognized = false
    }

    private fun begin(event: MotionEvent, current: PamGestureConfig) {
        cancel()
        active = true
        beganAt = event.eventTime
        startX = event.x
        startY = event.y
        startPageX = event.rawX
        startPageY = event.rawY
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        if (current.type == TYPE_LONG_PRESS) {
            view.postDelayed(longPress, current.minDurationMs.coerceAtLeast(1L))
        }
        if (current.type == TYPE_PAN && current.minDurationMs > 0L) {
            view.postDelayed(holdPan, current.minDurationMs)
        } else if (ownsImmediatePan(current)) {
            // A pan from the first pixel (a scrubber, a slider, a handle)
            // must get the first MOVE: a pager or list around it intercepts
            // as soon as the finger passes its touch slop.
            claimParent()
            tentativeParentClaim = true
        }
        if (current.type == TYPE_PINCH || current.type == TYPE_ROTATION) {
            captureMultiPointer(event)
        }
    }

    private fun move(event: MotionEvent, current: PamGestureConfig) {
        if (!active || event.pointerCount !in current.minPointers..current.maxPointers) return
        velocity?.addMovement(event)
        val local = displacementTo(event.rawX, event.rawY)
        val dx = local[0]
        val dy = local[1]
        val distance = hypot(dx, dy)
        when (current.type) {
            TYPE_PAN -> {
                if (!recognized && event.eventTime - beganAt >= current.minDurationMs &&
                    distance >= current.minDistance && matchesDirection(current, dx, dy)
                ) {
                    recognized = true
                    // A recognized exclusive/race pan owns the rest of the
                    // touch, as on iOS: the ancestor scroll can no longer
                    // take it over midway.
                    if (current.composition != COMPOSITION_SIMULTANEOUS) claimParent()
                    tentativeParentClaim = false
                    emitNow(payload(current, STATE_BEGAN, event))
                } else if (!recognized && tentativeParentClaim &&
                    hypot(event.rawX - startPageX, event.rawY - startPageY) >= touchSlop
                ) {
                    // Off-axis drag: hand the touch back to the ancestors.
                    tentativeParentClaim = false
                    releaseParent()
                }
                if (recognized) schedule(payload(current, STATE_CHANGED, event))
            }
            TYPE_PINCH -> {
                if (event.pointerCount < 2) return
                if (!recognized && abs(pointerDistance(event) - startDistance) >= current.minDistance) {
                    recognized = true
                    emitNow(payload(current, STATE_BEGAN, event))
                }
                if (recognized) schedule(payload(current, STATE_CHANGED, event))
            }
            TYPE_ROTATION -> {
                if (event.pointerCount < 2) return
                if (!recognized && abs(pointerAngle(event) - startAngle) >= MIN_ROTATION_RADIANS) {
                    recognized = true
                    emitNow(payload(current, STATE_BEGAN, event))
                }
                if (recognized) schedule(payload(current, STATE_CHANGED, event))
            }
            TYPE_LONG_PRESS -> if (recognized) {
                schedule(payload(current, STATE_CHANGED, event))
            }
        }
    }

    private fun finish(
        event: MotionEvent,
        current: PamGestureConfig,
        cancelled: Boolean,
    ) {
        if (!active) return
        velocity?.addMovement(event)
        velocity?.computeCurrentVelocity(1_000)
        view.removeCallbacks(longPress)
        // A tap's slop is physical (screen pixels); a swipe's distance and
        // direction are the detector's, like a pan.
        val screenDx = event.rawX - startPageX
        val screenDy = event.rawY - startPageY
        val local = displacementTo(event.rawX, event.rawY)
        val dx = local[0]
        val dy = local[1]
        val duration = event.eventTime - beganAt
        if (!cancelled && !recognized) {
            when (current.type) {
                TYPE_TAP -> if (
                    duration >= current.minDurationMs &&
                    hypot(screenDx, screenDy) <= current.minDistance.coerceAtLeast(DEFAULT_TAP_SLOP) &&
                    matchesDirection(current, screenDx, screenDy)
                ) {
                    recognized = true
                    emitNow(payload(current, STATE_BEGAN, event))
                }
                TYPE_SWIPE -> if (
                    hypot(dx, dy) >= current.minDistance &&
                    matchesDirection(current, dx, dy)
                ) {
                    recognized = true
                    emitNow(payload(current, STATE_BEGAN, event))
                }
            }
        }
        pendingUpdate?.let(::emitNow)
        pendingUpdate = null
        if (recognized) {
            emitNow(payload(current, if (cancelled) STATE_CANCELLED else STATE_ENDED, event))
        }
        cancel()
    }

    private fun schedule(payload: PamGesturePayload) {
        pendingUpdate = payload
        if (updateScheduled) return
        updateScheduled = true
        view.postOnAnimation(update)
    }

    private fun emitNow(payload: PamGesturePayload) {
        emit?.invoke(payload)
    }

    private fun payload(
        current: PamGestureConfig,
        state: Int,
        lastEvent: MotionEvent?,
    ): PamGesturePayload {
        val event = lastEvent
        val x = event?.x ?: startX
        val y = event?.y ?: startY
        val pageX = event?.rawX ?: startPageX
        val pageY = event?.rawY ?: startPageY
        val pointerCount = event?.pointerCount ?: 1
        val scale = if (event != null && event.pointerCount >= 2 && startDistance > 0f) {
            pointerDistance(event) / startDistance
        } else {
            1f
        }
        val rotation = if (event != null && event.pointerCount >= 2) {
            pointerAngle(event) - startAngle
        } else {
            0f
        }
        val translation = displacementTo(pageX, pageY)
        val translationX = translation[0]
        val translationY = translation[1]
        return PamGesturePayload(
            type = current.type,
            state = state,
            x = x,
            y = y,
            pageX = pageX,
            pageY = pageY,
            translationX = translationX,
            translationY = translationY,
            velocityX = velocity?.xVelocity ?: 0f,
            velocityY = velocity?.yVelocity ?: 0f,
            scale = scale,
            rotation = rotation,
            pointerCount = pointerCount,
            timestamp = event?.eventTime ?: SystemClock.uptimeMillis(),
            focalX = if (event != null && event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else x,
            focalY = if (event != null && event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else y,
        )
    }

    private fun refreshLocalSpace() {
        localSpaceTransformed = PamGestureSpace.screenToLocal(view, localSpace, matrixValues)
    }

    /**
     * The finger's displacement since touch-down in the detector's space
     * (iOS `translation(in: view)`), in a reused array. Without a scaled or
     * rotated ancestor it is the screen delta, unchanged.
     */
    private fun displacementTo(rawX: Float, rawY: Float): FloatArray {
        displacement[0] = rawX - startPageX
        displacement[1] = rawY - startPageY
        if (localSpaceTransformed) PamGestureSpace.map(localSpace, displacement)
        return displacement
    }

    private fun captureMultiPointer(event: MotionEvent) {
        if (event.pointerCount < 2) return
        startDistance = pointerDistance(event).coerceAtLeast(0.001f)
        startAngle = pointerAngle(event)
    }

    private fun pointerDistance(event: MotionEvent): Float =
        hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))

    private fun pointerAngle(event: MotionEvent): Float =
        atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0))

    private fun ownsImmediatePan(current: PamGestureConfig): Boolean =
        current.type == TYPE_PAN &&
            current.minDistance <= 0f &&
            current.composition != COMPOSITION_SIMULTANEOUS

    private fun claimParent() {
        if (panOwnsParent) return
        panOwnsParent = true
        view.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun releaseParent() {
        tentativeParentClaim = false
        if (!panOwnsParent) return
        panOwnsParent = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun matchesDirection(current: PamGestureConfig, dx: Float, dy: Float): Boolean =
        when (current.direction) {
            DIRECTION_LEFT -> dx < 0 && abs(dx) >= abs(dy)
            DIRECTION_RIGHT -> dx > 0 && abs(dx) >= abs(dy)
            DIRECTION_UP -> dy < 0 && abs(dy) >= abs(dx)
            DIRECTION_DOWN -> dy > 0 && abs(dy) >= abs(dx)
            DIRECTION_HORIZONTAL -> abs(dx) >= abs(dy)
            DIRECTION_VERTICAL -> abs(dy) >= abs(dx)
            else -> true
        }

    private companion object {
        const val TYPE_TAP = 1
        const val TYPE_PAN = 2
        const val TYPE_PINCH = 3
        const val TYPE_ROTATION = 4
        const val TYPE_SWIPE = 5
        const val TYPE_LONG_PRESS = 6
        const val STATE_BEGAN = 1
        const val STATE_CHANGED = 2
        const val STATE_ENDED = 3
        const val STATE_CANCELLED = 4
        const val DIRECTION_LEFT = 2
        const val DIRECTION_RIGHT = 3
        const val DIRECTION_UP = 4
        const val DIRECTION_DOWN = 5
        const val DIRECTION_HORIZONTAL = 6
        const val DIRECTION_VERTICAL = 7
        const val COMPOSITION_EXCLUSIVE = 1
        const val COMPOSITION_SIMULTANEOUS = 2
        const val COMPOSITION_RACE = 3
        const val DEFAULT_TAP_SLOP = 12f
        const val MIN_ROTATION_RADIANS = 0.035f
    }
}

/**
 * Gesture displacements in the detector's own space, like iOS
 * `translation(in: view)`. Android reports the finger in screen pixels
 * (`rawX`/`rawY`); under a scaled or rotated ancestor (a story sticker) that
 * is not the space the detector, its children and a drag target move in. The
 * linear part (scale, rotation, skew) of the detector's and its ancestors'
 * matrices maps a local vector to the screen, and its inverse maps a
 * displacement back. Offsets (left/top, scroll, translation) never apply to a
 * displacement, so a detector without scale or rotation keeps the screen
 * delta exactly. A 3D rotation contributes its affine part.
 *
 * Linear maps are row-major `[a, b, c, d]`: x' = a·x + b·y, y' = c·x + d·y.
 */
internal object PamGestureSpace {
    fun identity(out: FloatArray) {
        out[0] = 1f
        out[1] = 0f
        out[2] = 0f
        out[3] = 1f
    }

    fun isIdentity(linear: FloatArray): Boolean =
        linear[0] == 1f && linear[1] == 0f && linear[2] == 0f && linear[3] == 1f

    /** [into] = [a, b, c, d] · [into]: [into] maps from an inner space, the new map ends one ancestor further out. */
    fun concatOuter(a: Float, b: Float, c: Float, d: Float, into: FloatArray) {
        val m0 = into[0]
        val m1 = into[1]
        val m2 = into[2]
        val m3 = into[3]
        into[0] = a * m0 + b * m2
        into[1] = a * m1 + b * m3
        into[2] = c * m0 + d * m2
        into[3] = c * m1 + d * m3
    }

    /** Inverts [linear] in place; false (unchanged) when it is singular or not finite. */
    fun invert(linear: FloatArray): Boolean {
        val determinant = linear[0] * linear[3] - linear[1] * linear[2]
        if (!determinant.isFinite() || abs(determinant) < MIN_DETERMINANT) return false
        val a = linear[0]
        linear[0] = linear[3] / determinant
        linear[1] = -linear[1] / determinant
        linear[2] = -linear[2] / determinant
        linear[3] = a / determinant
        return true
    }

    /** Maps the vector in [vector] (x, y) through [linear], in place. */
    fun map(linear: FloatArray, vector: FloatArray) {
        val x = vector[0]
        val y = vector[1]
        vector[0] = linear[0] * x + linear[1] * y
        vector[1] = linear[2] * x + linear[3] * y
    }

    /**
     * Fills [out] with the map from a screen displacement to [view]'s space.
     * False when no matrix on the way scales or rotates (or it cannot be
     * inverted): the screen delta is used as it is. [values] is scratch.
     */
    fun screenToLocal(view: View, out: FloatArray, values: FloatArray): Boolean {
        identity(out)
        var node: View? = view
        while (node != null) {
            val matrix = node.matrix
            if (!matrix.isIdentity) {
                matrix.getValues(values)
                val a = values[Matrix.MSCALE_X]
                val b = values[Matrix.MSKEW_X]
                val c = values[Matrix.MSKEW_Y]
                val d = values[Matrix.MSCALE_Y]
                if (a != 1f || b != 0f || c != 0f || d != 1f) concatOuter(a, b, c, d, out)
            }
            node = node.parent as? View
        }
        return !isIdentity(out) && invert(out)
    }

    private const val MIN_DETERMINANT = 1e-9f
}
