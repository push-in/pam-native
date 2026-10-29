package dev.pam.nativeapp.render

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/**
 * Native visibility for p-intersect. Cell layout coordinates do not change
 * when their RecyclerView scrolls, so a layout listener alone misses exits
 * and reentries. Observe the drawn viewport and report only boolean edges.
 *
 * While the enclosing list settles a fling (or a programmatic smooth
 * scroll), edges are held and reported once it stops, with the visibility it
 * rests at: content swept past by a fling (autoplay sensors, impressions)
 * never wakes the app mid-fling, so no commit (and no player mounting)
 * competes with the scroll. A finger drag still reports live. Detaching
 * reports `false` at once (the view may be rebound to other content).
 */
internal class PamIntersectionObserver(
    private val view: View,
    private val changed: (Boolean) -> Unit,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val visibleRect = Rect()
    private var observer: ViewTreeObserver? = null
    private var lastVisible: Boolean? = null
    private var closed = false

    /** The list whose fling is holding an edge (listener installed). */
    private var settlingList: RecyclerView? = null
    private val settleListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (holdsVisibilityEdges(newState)) return
            releaseSettlingList()
            if (!closed && observer != null) publish(currentlyVisible())
        }
    }

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
        if (closed || settlingList != null) return true
        val visible = currentlyVisible()
        if (visible == lastVisible) return true
        val list = settlingAncestor()
        if (list != null) {
            settlingList = list
            list.addOnScrollListener(settleListener)
            return true
        }
        publish(visible)
        return true
    }

    private fun currentlyVisible(): Boolean =
        view.isAttachedToWindow && view.isShown && view.alpha > 0f &&
            view.getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty

    /** An enclosing list (any nesting level) that is settling a fling. */
    private fun settlingAncestor(): RecyclerView? {
        var parent = view.parent
        while (parent != null) {
            if (parent is RecyclerView && holdsVisibilityEdges(parent.scrollState)) return parent
            parent = parent.parent
        }
        return null
    }

    private fun releaseSettlingList() {
        settlingList?.removeOnScrollListener(settleListener)
        settlingList = null
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
        releaseSettlingList()
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
    }

    private fun publish(visible: Boolean) {
        if (lastVisible == visible) return
        lastVisible = visible
        changed(visible)
    }
}

/** A list settling a fling or a smooth scroll holds visibility edges; drags and rest do not. */
internal fun holdsVisibilityEdges(scrollState: Int): Boolean =
    scrollState == RecyclerView.SCROLL_STATE_SETTLING
