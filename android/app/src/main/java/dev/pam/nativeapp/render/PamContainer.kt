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
    private var overflowClipInsets = FloatArray(4)
    private var overflowClipPathDirty = true
    private val boxShadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val boxShadowPath = Path()
    private val boxShadowBounds = RectF()
    private var backdrop: PamBackdrop? = null
    private var shimmer: PamShimmer? = null

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (pointerEvents == POINTER_EVENTS_NONE) return false

        // ViewGroup's normal dispatch can accept the delegated ACTION_DOWN as
        // the container's own event and then skip the TouchDelegate on
        // ACTION_UP when the pointer started outside the child's visual
        // bounds. Route PAM's grouped delegates before child hit testing so a
        // compact 40dp control receives the complete 48dp Material gesture.
        val delegated = if (pointerEvents == POINTER_EVENTS_BOX_ONLY) {
            false
        } else {
            (touchDelegate as? PamTouchDelegateGroup)?.onTouchEvent(event) == true
        }
        return delegated || super.dispatchTouchEvent(event)
    }

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

    /** CSS backdrop-filter; a zero radius and null matrix remove it. */
    fun setBackdrop(radiusPx: Float, matrix: android.graphics.ColorMatrix?) {
        val effect = backdrop ?: if (radiusPx > 0f || matrix != null) {
            PamBackdrop(this).also { backdrop = it }
        } else {
            return
        }
        effect.configure(radiusPx, matrix)
        if (effect.active) {
            setWillNotDraw(false)
        } else {
            effect.detach()
            backdrop = null
        }
    }

    /** `<Shimmer>` skeleton sweep drawn over the background, under the children. */
    fun setShimmer(enabled: Boolean, color: Int, durationMs: Long) {
        val effect = shimmer ?: if (enabled) PamShimmer(this).also { shimmer = it } else return
        effect.configure(enabled, color, durationMs)
    }

    /**
     * React Native `needsOffscreenAlphaCompositing`. Off by default, like
     * RN's `ReactViewGroup`: an `opacity` < 1 is applied to every draw of the
     * subtree (the background, then each child over it) instead of fading
     * one offscreen layer, so a white label inside a faded button blends
     * with the faded fill exactly as in React Native Android. True restores
     * the single flattened layer (no overlap between translucent children).
     */
    var needsOffscreenAlphaCompositing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun hasOverlappingRendering(): Boolean = needsOffscreenAlphaCompositing

    override fun draw(canvas: Canvas) {
        backdrop?.draw(canvas, overflowClipRadii)
        super.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop?.attach()
        shimmer?.update()
    }

    override fun onDetachedFromWindow() {
        backdrop?.detach()
        super.onDetachedFromWindow()
        shimmer?.update()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        shimmer?.update()
    }

    override fun dispatchDraw(canvas: Canvas) {
        shimmer?.takeIf { it.enabled }?.let {
            updateOverflowClipPath()
            it.draw(canvas, overflowClipPath)
        }
        if (!overflowClipEnabled) {
            super.dispatchDraw(canvas)
            return
        }
        val checkpoint = canvas.save()
        updateOverflowClipPath()
        if (overflowClipRadii.any { it > 0f } && overflowClipPath.isEmpty.not()) {
            canvas.clipPath(overflowClipPath)
        } else {
            canvas.clipRect(overflowClipBounds)
        }
        super.dispatchDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        PamBoxShadows.draw(
            canvas,
            child,
            boxShadowPaint,
            boxShadowPath,
            boxShadowBounds,
        )

        return super.drawChild(canvas, child, drawingTime)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) {
            overflowClipPathDirty = true
            shimmer?.update()
        }
    }

    final override fun setPointerEvents(mode: Int) {
        pointerEvents = mode.coerceIn(POINTER_EVENTS_AUTO, POINTER_EVENTS_BOX_ONLY)
    }

    /**
     * React Native clips overflow to the padding box: inside the border, with
     * inner radii `max(radius - border, 0)`, so a border ring stays visible
     * around clipped content (avatars). [insets] are left, top, right, bottom.
     */
    fun setOverflowClip(enabled: Boolean, radii: FloatArray, insets: FloatArray = FloatArray(4)) {
        if (!overflowClipInsets.contentEquals(insets)) {
            overflowClipInsets = insets.copyOf()
            overflowClipPathDirty = true
        }
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
        val (left, top, right, bottom) = overflowClipInsets.toList()
        overflowClipBounds.set(left, top, width - right, height - bottom)
        val inner = FloatArray(CORNER_RADII_SIZE) { index ->
            val horizontalInset = if (index == 0 || index == 6) left else if (index == 2 || index == 4) right else 0f
            val verticalInset = if (index == 1 || index == 3) top else if (index == 5 || index == 7) bottom else 0f
            (overflowClipRadii[index] - horizontalInset - verticalInset).coerceAtLeast(0f)
        }
        overflowClipPath.reset()
        overflowClipPath.addRoundRect(
            overflowClipBounds,
            inner,
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
