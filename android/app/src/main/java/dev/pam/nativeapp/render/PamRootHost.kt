package dev.pam.nativeapp.render

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout

internal class PamRootHost(context: Context) : FrameLayout(context) {
    private val observers = LinkedHashSet<(MotionEvent) -> Unit>()
    var stableSafeAreaInsets: SafeAreaInsets = SafeAreaInsets(0, 0, 0, 0)
        private set

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        stableSafeAreaInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            SafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            @Suppress("DEPRECATION")
            SafeAreaInsets(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }
        return super.onApplyWindowInsets(insets)
    }

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

    fun startPredictiveBack(): Boolean = activeNavigationHost()?.startPredictiveBack() == true

    fun updatePredictiveBack(progress: Float) {
        activeNavigationHost()?.updatePredictiveBack(progress)
    }

    fun cancelPredictiveBack() {
        activeNavigationHost()?.cancelPredictiveBack()
    }

    fun commitPredictiveBack() {
        activeNavigationHost()?.commitPredictiveBack()
    }

    private fun activeNavigationHost(): PamNavigationHost? = findNavigationHost(this)

    private fun findNavigationHost(parent: ViewGroup): PamNavigationHost? {
        for (index in parent.childCount - 1 downTo 0) {
            val child = parent.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            if (child is ViewGroup) {
                findNavigationHost(child)?.let { return it }
            }
            if (child is PamNavigationHost && child.isShown) return child
        }
        return null
    }
}
