package dev.pam.nativeapp.render

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pre-30 adjustNothing windows receive neither IME insets nor a reduced visible
 * frame. A zero-width, non-interactive resize window can observe the keyboard
 * without resizing the Activity or taking focus away from its input.
 */
internal class PamLegacyImeObserver(
    private val anchor: View,
    private val hasVisibleOwner: () -> Boolean,
    private val onInsetChanged: (Int) -> Unit,
) : AutoCloseable {
    private val content = View(anchor.context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val popup = PopupWindow(content, 0, ViewGroup.LayoutParams.MATCH_PARENT, false).apply {
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        isTouchable = false
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        animationStyle = 0
    }
    private val frame = Rect()
    private val rootLocation = IntArray(2)
    private var closed = false
    private val contentLayout = ViewTreeObserver.OnGlobalLayoutListener { measure() }
    private var contentObserver: ViewTreeObserver? = null
    private val contentAttachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            contentObserver = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(contentLayout) }
        }
        override fun onViewDetachedFromWindow(view: View) {
            stopObservingContent()
        }
    }
    private val anchorLayout = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
    private val windowFocus = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
    private val show = Runnable { refresh() }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            observeAnchor()
            view.post(show)
        }
        override fun onViewDetachedFromWindow(view: View) {
            stopObservingAnchor()
            view.removeCallbacks(show)
            popup.dismiss()
            publish(0)
        }
    }
    private var anchorObserver: ViewTreeObserver? = null

    init {
        content.addOnAttachStateChangeListener(contentAttachment)
        anchor.addOnAttachStateChangeListener(attachment)
        if (anchor.isAttachedToWindow) {
            observeAnchor()
            anchor.post(show)
        }
    }

    private fun observeAnchor() {
        stopObservingAnchor()
        anchorObserver = anchor.viewTreeObserver.also {
            it.addOnGlobalLayoutListener(anchorLayout)
            it.addOnWindowFocusChangeListener(windowFocus)
        }
    }

    private fun stopObservingAnchor() {
        anchorObserver?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(anchorLayout)
            it.removeOnWindowFocusChangeListener(windowFocus)
        }
        anchorObserver = null
    }

    private fun stopObservingContent() {
        contentObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(contentLayout)
        contentObserver = null
    }

    fun refresh() {
        if (closed) return
        if (!anchor.isAttachedToWindow || !anchor.isShown || !anchor.hasWindowFocus() || !hasVisibleOwner()) {
            popup.dismiss()
            publish(0)
            return
        }
        if (!popup.isShowing) popup.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0)
        measure()
    }

    private fun measure() {
        if (closed || !popup.isShowing || !content.isLaidOut) return
        content.getWindowVisibleDisplayFrame(frame)
        if (frame.isEmpty) return
        val root = anchor.rootView
        root.getLocationOnScreen(rootLocation)
        val obstruction = (rootLocation[1] + root.height - frame.bottom).coerceAtLeast(0)
        val bars = ViewCompat.getRootWindowInsets(anchor)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        publish(if (obstruction > bars) obstruction else 0)
    }

    private fun publish(inset: Int) {
        // Visibility of consumers may change while the height stays the same.
        // Each consumer suppresses unchanged geometry before applying layout.
        onInsetChanged(inset)
    }

    override fun close() {
        closed = true
        anchor.removeCallbacks(show)
        anchor.removeOnAttachStateChangeListener(attachment)
        stopObservingAnchor()
        content.removeOnAttachStateChangeListener(contentAttachment)
        stopObservingContent()
        popup.dismiss()
    }
}
