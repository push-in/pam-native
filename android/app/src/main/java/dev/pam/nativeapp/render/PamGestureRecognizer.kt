package dev.pam.nativeapp.render

import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
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
        if (current.type == TYPE_PINCH || current.type == TYPE_ROTATION) {
            captureMultiPointer(event)
        }
    }

    private fun move(event: MotionEvent, current: PamGestureConfig) {
        if (!active || event.pointerCount !in current.minPointers..current.maxPointers) return
        velocity?.addMovement(event)
        val dx = event.x - startX
        val dy = event.y - startY
        val distance = hypot(dx, dy)
        when (current.type) {
            TYPE_PAN -> {
                if (!recognized && distance >= current.minDistance && matchesDirection(current, dx, dy)) {
                    recognized = true
                    emitNow(payload(current, STATE_BEGAN, event))
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
        val dx = event.x - startX
        val dy = event.y - startY
        val duration = event.eventTime - beganAt
        if (!cancelled && !recognized) {
            when (current.type) {
                TYPE_TAP -> if (
                    duration >= current.minDurationMs &&
                    hypot(dx, dy) <= current.minDistance.coerceAtLeast(DEFAULT_TAP_SLOP) &&
                    matchesDirection(current, dx, dy)
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
        return PamGesturePayload(
            type = current.type,
            state = state,
            x = x,
            y = y,
            pageX = pageX,
            pageY = pageY,
            translationX = x - startX,
            translationY = y - startY,
            velocityX = velocity?.xVelocity ?: 0f,
            velocityY = velocity?.yVelocity ?: 0f,
            scale = scale,
            rotation = rotation,
            pointerCount = pointerCount,
            timestamp = event?.eventTime ?: SystemClock.uptimeMillis(),
        )
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
        const val COMPOSITION_RACE = 3
        const val DEFAULT_TAP_SLOP = 12f
        const val MIN_ROTATION_RADIANS = 0.035f
    }
}
