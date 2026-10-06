package dev.pam.nativeapp.render

import android.view.View

/** One legacy measurement window shared by attached keyboard consumers. */
internal class PamLegacyImeInsets(private val host: PamRootHost) {
    private val listeners = LinkedHashMap<View, (Int) -> Unit>()
    private var observer: PamLegacyImeObserver? = null
    var bottom: Int = 0
        private set

    fun subscribe(owner: View, listener: (Int) -> Unit): AutoCloseable {
        var closed = false
        fun attach() {
            if (closed || owner.rootView !== host.rootView) return
            listeners[owner] = listener
            if (observer == null) {
                observer = PamLegacyImeObserver(host, { listeners.keys.any(View::isShown) }, ::dispatch)
            }
            listener(if (owner.isShown) bottom else 0)
            observer?.refresh()
        }
        fun detach() {
            listeners.remove(owner)
            if (listeners.isEmpty()) {
                observer?.close()
                observer = null
                bottom = 0
            }
        }
        val attachment = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = attach()
            override fun onViewDetachedFromWindow(view: View) {
                detach()
                listener(0)
            }
        }
        owner.addOnAttachStateChangeListener(attachment)
        if (owner.isAttachedToWindow) attach()
        return AutoCloseable {
            if (!closed) {
                closed = true
                owner.removeOnAttachStateChangeListener(attachment)
                detach()
            }
        }
    }

    private fun dispatch(inset: Int) {
        bottom = inset
        listeners.toList().forEach { (owner, listener) ->
            listener(if (owner.isShown) inset else 0)
        }
    }
}
