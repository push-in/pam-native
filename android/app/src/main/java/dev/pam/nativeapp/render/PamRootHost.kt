package dev.pam.nativeapp.render

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

internal class PamRootHost(context: Context) : FrameLayout(context) {
    private val observers = LinkedHashSet<(MotionEvent) -> Unit>()

    fun addPointerObserver(observer: (MotionEvent) -> Unit) {
        observers += observer
    }

    fun removePointerObserver(observer: (MotionEvent) -> Unit) {
        observers -= observer
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            observers.toList().forEach { it(event) }
        }
        return super.dispatchTouchEvent(event)
    }
}
