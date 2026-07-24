package dev.pam.nativeapp.render

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

internal class PamImageBackground(context: Context) :
    FrameLayout(context),
    PamPointerEventsHost {
    private var pointerEvents = POINTER_EVENTS_AUTO

    val image = PamImageView(context)

    init {
        clipChildren = false
        clipToPadding = false
        addView(
            image,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun insert(view: View, index: Int) {
        addView(view, (index + 1).coerceIn(1, childCount))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        pointerEvents != POINTER_EVENTS_NONE && super.dispatchTouchEvent(event)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        when (pointerEvents) {
            POINTER_EVENTS_BOX_ONLY -> true
            POINTER_EVENTS_BOX_NONE -> false
            else -> super.onInterceptTouchEvent(event)
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pointerEvents == POINTER_EVENTS_BOX_NONE) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            isPressed = false
            return performClick()
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun setPointerEvents(mode: Int) {
        pointerEvents = mode.coerceIn(POINTER_EVENTS_AUTO, POINTER_EVENTS_BOX_ONLY)
    }
}
