package dev.pam.nativeapp.render

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver

/**
 * Native visibility for p-intersect. Cell layout coordinates do not change
 * when their RecyclerView scrolls, so a layout listener alone misses exits
 * and reentries. Observe the drawn viewport and report only boolean edges.
 */
internal class PamIntersectionObserver(
    private val view: View,
    private val changed: (Boolean) -> Unit,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val visibleRect = Rect()
    private var observer: ViewTreeObserver? = null
    private var lastVisible: Boolean? = null
    private var closed = false

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) attach() else publish(false)
    }

    override fun onViewAttachedToWindow(view: View) = attach()

    override fun onViewDetachedFromWindow(view: View) {
        detach()
        publish(false)
    }

    override fun onPreDraw(): Boolean {
        if (!closed) {
            publish(
                view.isAttachedToWindow && view.isShown && view.alpha > 0f &&
                    view.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty,
            )
        }
        return true
    }

    fun close(notify: Boolean = true) {
        if (closed) return
        closed = true
        detach()
        view.removeOnAttachStateChangeListener(this)
        if (notify) publish(false)
    }

    private fun attach() {
        if (closed) return
        detach()
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        // The first actual frame has authoritative bounds after cell binding.
        view.invalidate()
    }

    private fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    private fun publish(visible: Boolean) {
        if (lastVisible == visible) return
        lastVisible = visible
        changed(visible)
    }
}
