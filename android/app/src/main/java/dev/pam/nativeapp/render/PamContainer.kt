package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

internal open class PamContainer(context: Context) :
    FrameLayout(context),
    PamPointerEventsHost {
    private var pointerEvents = POINTER_EVENTS_AUTO
    private var overflowClipEnabled = false
    private val overflowClipPath = Path()
    private val overflowClipBounds = RectF()
    private var overflowClipRadii = FloatArray(CORNER_RADII_SIZE)
    private var overflowClipPathDirty = true

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        pointerEvents != POINTER_EVENTS_NONE && super.dispatchTouchEvent(event)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        when (pointerEvents) {
            POINTER_EVENTS_BOX_ONLY -> true
            POINTER_EVENTS_BOX_NONE -> false
            else -> super.onInterceptTouchEvent(event)
        }

    open override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pointerEvents == POINTER_EVENTS_BOX_NONE) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            isPressed = false
            return performClick()
        }
        return super.onTouchEvent(event)
    }

    open override fun performClick(): Boolean = super.performClick()

    override fun dispatchDraw(canvas: Canvas) {
        if (!overflowClipEnabled) {
            super.dispatchDraw(canvas)
            return
        }
        val checkpoint = canvas.save()
        updateOverflowClipPath()
        if (overflowClipRadii.any { it > 0f }) {
            canvas.clipPath(overflowClipPath)
        } else {
            canvas.clipRect(overflowClipBounds)
        }
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            overflowClipPathDirty = true
        }
    }

    final override fun setPointerEvents(mode: Int) {
        pointerEvents = mode.coerceIn(POINTER_EVENTS_AUTO, POINTER_EVENTS_BOX_ONLY)
    }

    fun setOverflowClip(enabled: Boolean, radii: FloatArray) {
        require(radii.size == CORNER_RADII_SIZE) {
            "Expected $CORNER_RADII_SIZE corner radius values, received ${radii.size}"
        }
        val sanitizedRadii = FloatArray(CORNER_RADII_SIZE) { index ->
            radii[index].coerceAtLeast(0f)
        }
        val changed = overflowClipEnabled != enabled ||
            !overflowClipRadii.contentEquals(sanitizedRadii)
        overflowClipEnabled = enabled
        overflowClipRadii = sanitizedRadii
        clipChildren = enabled
        clipToPadding = false
        if (changed) {
            overflowClipPathDirty = true
            invalidate()
        }
    }

    open fun insert(view: View, index: Int) {
        val safeIndex = index.coerceIn(0, childCount)
        addView(view, safeIndex)
    }

    private fun updateOverflowClipPath() {
        if (!overflowClipPathDirty) return
        overflowClipBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        overflowClipPath.reset()
        overflowClipPath.addRoundRect(
            overflowClipBounds,
            overflowClipRadii,
            Path.Direction.CW,
        )
        overflowClipPath.close()
        overflowClipPathDirty = false
    }

    private companion object {
        const val CORNER_RADII_SIZE = 8
    }
}

internal interface PamPointerEventsHost {
    fun setPointerEvents(mode: Int)
}

internal const val POINTER_EVENTS_AUTO = 1
internal const val POINTER_EVENTS_NONE = 2
internal const val POINTER_EVENTS_BOX_NONE = 3
internal const val POINTER_EVENTS_BOX_ONLY = 4
