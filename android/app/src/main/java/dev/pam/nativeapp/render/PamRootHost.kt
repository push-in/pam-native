package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

internal fun isPamViewDrawnAbove(
    candidateZ: Float,
    candidateIndex: Int,
    branchZ: Float,
    branchIndex: Int,
): Boolean = candidateZ > branchZ ||
    (candidateZ == branchZ && candidateIndex > branchIndex)

internal class PamRootHost(context: Context) : FrameLayout(context) {
    private val statusBarSurfacePaint = Paint()
    private val observers = LinkedHashSet<(MotionEvent) -> Unit>()
    private val translatedTouchContainers = LinkedHashMap<View, Boolean>()
    private val translatedTouchTargets = LinkedHashSet<View>()
    private var translatedTouchTarget: View? = null
    var stableSafeAreaInsets: SafeAreaInsets = SafeAreaInsets(0, 0, 0, 0)
        private set
    var consumesBottomSystemInset: Boolean = false
        private set
    var consumedBottomSystemInset: Int = 0
        private set
    var onStableInsetsChanged: (() -> Unit)? = null
    internal var statusBarSurfaceColor: Int = Color.TRANSPARENT
        private set

    fun setStatusBarSurfaceColor(color: Int) {
        if (statusBarSurfaceColor == color) return
        statusBarSurfaceColor = color
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val inset = stableSafeAreaInsets.top
        if (inset > 0 && Color.alpha(statusBarSurfaceColor) > 0) {
            statusBarSurfacePaint.color = statusBarSurfaceColor
            canvas.drawRect(0f, 0f, width.toFloat(), inset.toFloat(), statusBarSurfacePaint)
        }
    }

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
        translatedTouchContainers.keys.removeAll { registered ->
            registered === container || isDescendantOf(registered, container) ||
                !registered.isAttachedToWindow
        }
        translatedTouchTargets.removeAll { target ->
            target === container || isDescendantOf(target, container) || !target.isAttachedToWindow
        }
        if (enabled && container is ViewGroup) {
            translatedTouchContainers[container] = includePressables
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
            refreshTranslatedTouchTargets()
            val target = translatedTouchTargets.toList().asReversed().firstOrNull { child ->
                containsScreenPoint(child, event.rawX, event.rawY) &&
                    !isOccludedByHigherSibling(child, event.rawX, event.rawY)
            }
            if (target != null) {
                translatedTouchTarget = target
                return dispatchToTranslatedTarget(target, event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun refreshTranslatedTouchTargets() {
        translatedTouchTargets.clear()
        translatedTouchContainers.entries.removeAll { (container, _) ->
            !container.isAttachedToWindow
        }
        translatedTouchContainers.forEach { (container, includePressables) ->
            if (container is ViewGroup) collectTranslatedTargets(container, includePressables)
        }
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

    /**
     * Translated IME targets are dispatched before Android's regular ViewGroup hit test because
     * they can extend outside a panned ancestor. They must still respect the visual stacking order:
     * an absolute overlay (for example a camera or media composer) drawn above the input owns the
     * pointer even when the old input rectangle remains underneath it.
     */
    private fun isOccludedByHigherSibling(target: View, x: Float, y: Float): Boolean {
        var branch = target
        var parent = branch.parent as? ViewGroup
        while (parent != null) {
            val branchIndex = parent.indexOfChild(branch)
            repeat(parent.childCount) { index ->
                val sibling = parent.getChildAt(index)
                if (
                    sibling !== branch &&
                    isPamViewDrawnAbove(sibling.z, index, branch.z, branchIndex) &&
                    containsScreenPoint(sibling, x, y)
                ) {
                    return true
                }
            }
            branch = parent
            if (branch === this) break
            parent = branch.parent as? ViewGroup
        }
        return false
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
