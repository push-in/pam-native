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

internal fun isEligibleTranslatedTouchOccluder(
    isScrollContainer: Boolean,
    containsInteractiveTarget: Boolean,
): Boolean = !isScrollContainer && containsInteractiveTarget

private const val IME_RECONCILE_DELAY_MS = 350L

internal class PamRootHost(context: Context) : FrameLayout(context) {
    internal val legacyImeInsets by lazy(LazyThreadSafetyMode.NONE) { PamLegacyImeInsets(this) }
    private val statusBarSurfacePaint = Paint()
    private val observers = LinkedHashSet<(MotionEvent) -> Unit>()
    private val translatedTouchContainers = LinkedHashMap<View, Boolean>()
    private val translatedTouchTargets = LinkedHashSet<View>()
    private var translatedTouchTarget: View? = null
    var stableSafeAreaInsets: SafeAreaInsets = SafeAreaInsets(0, 0, 0, 0)
        private set
    private var stableInsetsSize: Pair<Int, Int>? = null
    var consumesBottomSystemInset: Boolean = false
        private set
    var consumedBottomSystemInset: Int = 0
        private set
    var onStableInsetsChanged: (() -> Unit)? = null

    /** Visible IME height in pixels from the window bottom (0 when hidden). */
    var imeBottomInset: Int = 0
        private set

    /** Called synchronously while insets are dispatched, before layout. */
    var onImeInsetChanged: ((Int) -> Unit)? = null
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

    /** Whether the owning window has input focus (another window may be on top). */
    var windowFocused: () -> Boolean = { hasWindowFocus() }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val previous = stableSafeAreaInsets
        val previousSize = stableInsetsSize
        stableInsetsSize = width to height
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
        val sameWindow = previousSize == null || previousSize == stableInsetsSize ||
            previousSize.first == 0 || previousSize.second == 0
        if (!windowFocused() && sameWindow) {
            // A dialog on top must not shrink this window's safe area.
            stableSafeAreaInsets = SafeAreaInsets(
                maxOf(previous.left, stableSafeAreaInsets.left),
                maxOf(previous.top, stableSafeAreaInsets.top),
                maxOf(previous.right, stableSafeAreaInsets.right),
                maxOf(previous.bottom, stableSafeAreaInsets.bottom),
            )
        }
        if (stableSafeAreaInsets != previous) post { onStableInsetsChanged?.invoke() }
        updateImeInset(imeInsetOf(insets))
        // The end-state insets dispatched before an IME animation can differ
        // from the settled IME (a suggestion strip appearing or not); the
        // root window insets are authoritative once the transition settles.
        removeCallbacks(imeReconciliation)
        postDelayed(imeReconciliation, IME_RECONCILE_DELAY_MS)
        return super.onApplyWindowInsets(insets)
    }

    /**
     * The user closed the keyboard (Back, the IME's own hide key or a system
     * gesture) while a text input kept focus. Blur it, so `on:blur` observers
     * (a chat composer restoring its safe-area padding) see the same state as
     * the screen: a hidden keyboard and no caret. The root host absorbs the
     * focus; otherwise Android would refocus the first focusable input.
     */
    private val imeReconciliation = Runnable { reconcileImeInset() }

    private fun imeInsetOf(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            visibleImeInset(
                rawInset = insets.getInsets(WindowInsets.Type.ime()).bottom,
                visible = insets.isVisible(WindowInsets.Type.ime()),
            )
        } else {
            0
        }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            rootWindowInsets?.let { updateImeInset(imeInsetOf(it)) }
            // Re-read the real insets that were retained while unfocused.
            requestApplyInsets()
        }
    }

    /** Re-reads the settled IME height (after an insets animation ends). */
    fun reconcileImeInset() {
        if (!isAttachedToWindow) return
        val settled = rootWindowInsets?.let(::imeInsetOf) ?: return
        // Only the height is reconciled: visibility follows the dispatched
        // insets, because the root insets lag behind while the IME animates.
        if ((settled > 0) == (imeBottomInset > 0)) updateImeInset(settled)
    }

    private fun updateImeInset(ime: Int) {
        if (ime == imeBottomInset) return
        // The IME of another (dialog) window does not cover this window's
        // composer; only hiding is applied while unfocused.
        if (ime > 0 && !windowFocused()) return
        val hidden = imeBottomInset > 0 && ime == 0
        imeBottomInset = ime
        onImeInsetChanged?.invoke(ime)
        if (hidden) post(::blurInputAfterImeHidden)
    }

    private fun blurInputAfterImeHidden() {
        if (imeBottomInset != 0 || !isAttachedToWindow) return
        val focused = findFocus() as? android.widget.EditText ?: return
        isFocusableInTouchMode = true
        if (!requestFocus()) focused.clearFocus()
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
                    containsScreenPoint(sibling, x, y) &&
                    isEligibleTranslatedTouchOccluder(
                        isScrollContainer = sibling is PamScrollContainer,
                        containsInteractiveTarget = containsInteractiveTargetAtPoint(sibling, x, y),
                    )
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

    private fun containsInteractiveTargetAtPoint(view: View, x: Float, y: Float): Boolean {
        if (!containsScreenPoint(view, x, y)) return false
        if (view is PamPressable || view is PamEditText) return true
        if (view !is ViewGroup) return false
        for (index in view.childCount - 1 downTo 0) {
            if (containsInteractiveTargetAtPoint(view.getChildAt(index), x, y)) return true
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
