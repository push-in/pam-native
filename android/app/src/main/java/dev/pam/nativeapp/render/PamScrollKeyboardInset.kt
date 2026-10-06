package dev.pam.nativeapp.render

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.core.view.WindowInsetsCompat

/** Keyboard-aware content inset; the scroll viewport remains full height. */
internal class PamScrollKeyboardInset(
    private val scroll: PamScrollContainer,
) : AutoCloseable {
    var extraInsetPx: Int = 0
        set(value) {
            val next = value.coerceAtLeast(0)
            if (field == next) return
            field = next
            scroll.requestApplyInsets()
        }

    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            scroll.requestApplyInsets()
        }
    }

    init {
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            apply(insets)
            insets
        }
        scroll.addOnLayoutChangeListener(layoutListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                    apply(insets)
                    return insets
                }
            })
        }
        scroll.requestApplyInsets()
    }

    private fun apply(insets: WindowInsets) {
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, scroll)
        val ime = if (compat.isVisible(WindowInsetsCompat.Type.ime())) {
            compat.getInsets(WindowInsetsCompat.Type.ime()).bottom
        } else {
            0
        }
        val location = IntArray(2)
        scroll.getLocationInWindow(location)
        val below = (scroll.rootView.height - (location[1] + scroll.height)).coerceAtLeast(0)
        val overlap = (ime - below).coerceAtLeast(0)
        val next = if (overlap > 0) overlap + extraInsetPx else 0
        // Repeated unchanged insets must not undo a user's manual scroll.
        if (next == scroll.keyboardAvoidanceInsetPixels()) return
        scroll.setKeyboardAvoidanceInset(next)
        if (next > 0) scroll.findFocus()?.let(scroll::ensureKeyboardTargetVisible)
    }

    override fun close() {
        scroll.setOnApplyWindowInsetsListener(null)
        scroll.removeOnLayoutChangeListener(layoutListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) scroll.setWindowInsetsAnimationCallback(null)
        scroll.setKeyboardAvoidanceInset(0)
    }
}
