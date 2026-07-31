package dev.pam.nativeapp.render

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

internal fun shouldRegisterTranslatedTouchTarget(
    isInput: Boolean,
    isPressable: Boolean,
    includePressables: Boolean,
): Boolean = isInput || (includePressables && isPressable)

internal class PamRootHost(context: Context) : FrameLayout(context) {
    private val observers = LinkedHashSet<(MotionEvent) -> Unit>()
    private val translatedTouchTargets = LinkedHashSet<View>()
    private var translatedTouchTarget: View? = null
    var stableSafeAreaInsets: SafeAreaInsets = SafeAreaInsets(0, 0, 0, 0)
        private set
    var consumesBottomSystemInset: Boolean = false
        private set
    var consumedBottomSystemInset: Int = 0
        private set
    var onStableInsetsChanged: (() -> Unit)? = null

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val previous = stableSafeAreaInsets
        stableSafeAreaInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            consumesBottomSystemInset = false
            consumedBottomSystemInset = 0
            SafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            @Suppress("DEPRECATION")
            val safe = SafeAreaInsets(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
            consumesBottomSystemInset = false
            consumedBottomSystemInset = 0
            safe
        }
        if (stableSafeAreaInsets != previous) post { onStableInsetsChanged?.invoke() }
        return super.onApplyWindowInsets(insets)
    }

    fun addPointerObserver(observer: (MotionEvent) -> Unit) {
        observers += observer
    }

    fun removePointerObserver(observer: (MotionEvent) -> Unit) {
        observers -= observer
    }

    fun replaceTranslatedTouchTargets(
        container: View,
        enabled: Boolean,
        includePressables: Boolean = true,
    ) {
        translatedTouchTargets.removeAll { target ->
            target === container || isDescendantOf(target, container) || !target.isAttachedToWindow
        }
        if (enabled && container is ViewGroup) {
            collectTranslatedTargets(container, includePressables)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            observers.toList().forEach { it(event) }
        }
        translatedTouchTarget?.let { target ->
            val handled = dispatchToTranslatedTarget(target, event)
            if (
                event.actionMasked == MotionEvent.ACTION_UP
                || event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                translatedTouchTarget = null
            }
            return handled
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val target = translatedTouchTargets.toList().asReversed().firstOrNull { child ->
                containsScreenPoint(child, event.rawX, event.rawY)
            }
            if (target != null) {
                translatedTouchTarget = target
                return dispatchToTranslatedTarget(target, event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun dispatchToTranslatedTarget(target: View, event: MotionEvent): Boolean {
        if (!target.isAttachedToWindow) return false
        if (event.actionMasked == MotionEvent.ACTION_UP && target is PamPressable) {
            target.performClick()
            return true
        }
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val local = MotionEvent.obtain(event)
        local.setLocation(event.rawX - location[0], event.rawY - location[1])
        val handled = target.dispatchTouchEvent(local)
        local.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP && target is PamEditText) {
            if (!target.hasFocus()) target.requestFocus()
            target.post {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            }
            return true
        }
        return handled
    }

    private fun collectTranslatedTargets(parent: ViewGroup, includePressables: Boolean) {
        repeat(parent.childCount) { index ->
            val child = parent.getChildAt(index)
            if (
                shouldRegisterTranslatedTouchTarget(
                    isInput = child is PamEditText,
                    isPressable = child is PamPressable,
                    includePressables = includePressables,
                )
            ) {
                translatedTouchTargets += child
            }
            if (child is ViewGroup) collectTranslatedTargets(child, includePressables)
        }
    }

    private fun isDescendantOf(target: View, container: View): Boolean {
        var ancestor = target.parent as? View
        while (ancestor != null) {
            if (ancestor === container) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun containsScreenPoint(target: View, x: Float, y: Float): Boolean {
        if (!target.isShown || target.alpha <= 0f) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + target.width &&
            y >= location[1] && y < location[1] + target.height
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
